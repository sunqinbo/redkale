/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.http;

import org.redkale.net.http.WebSocketPacket.FrameType;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.redkale.convert.json.JsonConvert;
import org.redkale.net.*;
import org.redkale.util.Comment;

/**
 * <blockquote><pre>
 * 一个WebSocket连接对应一个WebSocket实体，即一个WebSocket会绑定一个TCP连接。
 * WebSocket 有两种模式:
 *  1) 普通模式: 协议上符合HTML5规范, 其流程顺序如下:
 *      1.1 onOpen 若返回null，视为WebSocket的连接不合法，强制关闭WebSocket连接；通常用于判断登录态。
 *      1.2 createGroupid 若返回null，视为WebSocket的连接不合法，强制关闭WebSocket连接；通常用于判断用户权限是否符合。
 *      1.3 onConnected WebSocket成功连接后在准备接收数据前回调此方法。
 *      1.4 onMessage/onFragment+ WebSocket接收到消息后回调此消息类方法。
 *      1.5 onClose WebSocket被关闭后回调此方法。
 *  普通模式下 以上方法都应该被重载。
 *
 *  2) 原始二进制模式: 此模式有别于HTML5规范，可以视为原始的TCP连接。通常用于音频视频通讯场景。其流程顺序如下:
 *      2.1 onOpen 若返回null，视为WebSocket的连接不合法，强制关闭WebSocket连接；通常用于判断登录态。
 *      2.2 createGroupid 若返回null，视为WebSocket的连接不合法，强制关闭WebSocket连接；通常用于判断用户权限是否符合。
 *      2.3 onRead WebSocket成功连接后回调此方法， 由此方法处理原始的TCP连接， 需要业务代码去控制WebSocket的关闭。
 *  二进制模式下 以上方法都应该被重载。
 * </pre></blockquote>
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @param <G> Groupid的泛型
 * @param <T> Message的泛型
 */
public abstract class WebSocket<G extends Serializable, T> {

    @Comment("消息不合法")
    public static final int RETCODE_SEND_ILLPACKET = 1 << 1; //2

    @Comment("WebSocket已经关闭")
    public static final int RETCODE_WSOCKET_CLOSED = 1 << 2; //4

    @Comment("Socket的buffer不合法")
    public static final int RETCODE_ILLEGALBUFFER = 1 << 3; //8

    @Comment("WebSocket发送消息异常")
    public static final int RETCODE_SENDEXCEPTION = 1 << 4; //16

    @Comment("WebSocketEngine实例不存在")
    public static final int RETCODE_ENGINE_NULL = 1 << 5; //32

    @Comment("WebSocketNode实例不存在")
    public static final int RETCODE_NODESERVICE_NULL = 1 << 6; //64

    @Comment("WebSocket组为空, 表示无WebSocket连接")
    public static final int RETCODE_GROUP_EMPTY = 1 << 7; //128

    @Comment("WebSocket已离线")
    public static final int RETCODE_WSOFFLINE = 1 << 8; //256

    WebSocketRunner _runner; //不可能为空 

    WebSocketEngine _engine; //不可能为空 

    WebSocketGroup _group; //不可能为空 

    String _sessionid; //不可能为空 

    G _groupid; //不可能为空 

    SocketAddress _remoteAddress;//不可能为空 

    String _remoteAddr;//不可能为空 

    JsonConvert _jsonConvert; //不可能为空 

    java.lang.reflect.Type _messageTextType; //不可能为空

    private long createtime = System.currentTimeMillis();

    private Map<String, Object> attributes = new HashMap<>(); //非线程安全

    protected long websocketid = Math.abs(System.nanoTime()); //唯一ID

    protected WebSocket() {
    }

    //----------------------------------------------------------------
    public final CompletableFuture<Integer> sendPing() {
        //if (_engine.finest) _engine.logger.finest(this + " on "+_engine.getEngineid()+" ping...");
        return sendPacket(WebSocketPacket.DEFAULT_PING_PACKET);
    }

    public final CompletableFuture<Integer> sendPing(byte[] data) {
        return sendPacket(new WebSocketPacket(FrameType.PING, data));
    }

    public final CompletableFuture<Integer> sendPong(byte[] data) {
        return sendPacket(new WebSocketPacket(FrameType.PONG, data));
    }

    public final long getCreatetime() {
        return createtime;
    }

    /**
     * 给自身发送消息, 消息类型是String或byte[]或可JavaBean对象
     *
     * @param message 不可为空, 只能是String或byte[]或可JavaBean对象
     *
     * @return 0表示成功， 非0表示错误码
     */
    public final CompletableFuture<Integer> send(Object message) {
        return send(message, true);
    }

    /**
     * 给自身发送消息, 消息类型是String或byte[]或可JavaBean对象
     *
     * @param message 不可为空, 只能是String或byte[]或可JavaBean对象
     * @param last    是否最后一条
     *
     * @return 0表示成功， 非0表示错误码
     */
    public final CompletableFuture<Integer> send(Object message, boolean last) {
        if (message instanceof CompletableFuture) {
            return ((CompletableFuture) message).thenCompose((json) -> {
                if (json == null || json instanceof CharSequence || json instanceof byte[]) {
                    return sendPacket(new WebSocketPacket((Serializable) json, last));
                } else if (message instanceof WebSocketPacket) {
                    return sendPacket((WebSocketPacket) message);
                } else {
                    return sendPacket(new WebSocketPacket(_jsonConvert, json, last));
                }
            });
        }
        if (message == null || message instanceof CharSequence || message instanceof byte[]) {
            return sendPacket(new WebSocketPacket((Serializable) message, last));
        } else if (message instanceof WebSocketPacket) {
            return sendPacket((WebSocketPacket) message);
        } else {
            return sendPacket(new WebSocketPacket(_jsonConvert, message, last));
        }
    }

    /**
     * 给自身发送消息, 消息类型是JavaBean对象
     *
     * @param convert JsonConvert
     * @param message 不可为空, 只能是JSON对象
     *
     * @return 0表示成功， 非0表示错误码
     */
    public final CompletableFuture<Integer> send(JsonConvert convert, Object message) {
        return send(convert, message, true);
    }

    /**
     * 给自身发送消息, 消息类型是JavaBean对象
     *
     * @param convert JsonConvert
     * @param message 不可为空, 只能是JavaBean对象
     * @param last    是否最后一条
     *
     * @return 0表示成功， 非0表示错误码
     */
    public final CompletableFuture<Integer> send(JsonConvert convert, Object message, boolean last) {
        if (message instanceof CompletableFuture) {
            return ((CompletableFuture) message).thenCompose((json) -> sendPacket(new WebSocketPacket(convert == null ? _jsonConvert : convert, json, last)));
        }
        return sendPacket(new WebSocketPacket(convert == null ? _jsonConvert : convert, message, last));
    }

    /**
     * 给自身发送消息体, 包含二进制/文本
     *
     * @param packet WebSocketPacket
     *
     * @return 0表示成功， 非0表示错误码
     */
    CompletableFuture<Integer> sendPacket(WebSocketPacket packet) {
        CompletableFuture<Integer> rs = this._runner.sendMessage(packet);
        if (_engine.finest) _engine.logger.finest("wsgroupid:" + getGroupid() + " send websocket result is " + rs + " on " + this + " by message(" + packet + ")");
        return rs == null ? CompletableFuture.completedFuture(RETCODE_WSOCKET_CLOSED) : rs;
    }

    //----------------------------------------------------------------
    /**
     * 给指定groupid的WebSocketGroup下所有WebSocket节点发送 二进制消息/文本消息/JavaBean对象消息
     *
     * @param message  不可为空
     * @param groupids Serializable[]
     *
     * @return 为0表示成功， 其他值表示异常
     */
    public final CompletableFuture<Integer> sendEachMessage(Object message, G... groupids) {
        return sendEachMessage(message, true, groupids);
    }

    /**
     * 给指定groupid的WebSocketGroup下所有WebSocket节点发送 二进制消息/文本消息/JavaBean对象消息
     *
     * @param message  不可为空
     * @param last     是否最后一条
     * @param groupids Serializable[]
     *
     * @return 为0表示成功， 其他值表示异常
     */
    public final CompletableFuture<Integer> sendEachMessage(Object message, boolean last, G... groupids) {
        return sendMessage(false, message, last, groupids);
    }

    /**
     * 给指定groupid的WebSocketGroup下最近接入的WebSocket节点发送 二进制消息/文本消息/JavaBean对象消息
     *
     * @param message  不可为空
     * @param groupids Serializable[]
     *
     * @return 为0表示成功， 其他值表示异常
     */
    public final CompletableFuture<Integer> sendRecentMessage(Object message, G... groupids) {
        return sendMessage(true, message, true, groupids);
    }

    /**
     * 给指定groupid的WebSocketGroup下最近接入的WebSocket节点发送 二进制消息/文本消息/JavaBean对象消息
     *
     * @param groupids Serializable[]
     * @param message  不可为空
     * @param last     是否最后一条
     *
     * @return 为0表示成功， 其他值表示异常
     */
    public final CompletableFuture<Integer> sendRecentMessage(Object message, boolean last, G... groupids) {
        return sendMessage(true, message, last, groupids);
    }

    /**
     * 给指定groupid的WebSocketGroup下WebSocket节点发送 二进制消息/文本消息/JavaBean对象消息
     *
     * @param recent   是否只发最近接入的WebSocket
     * @param message  不可为空
     * @param last     是否最后一条
     * @param groupids Serializable[]
     *
     * @return 为0表示成功， 其他值表示异常
     */
    public final CompletableFuture<Integer> sendMessage(boolean recent, Object message, boolean last, G... groupids) {
        if (_engine.node == null) return CompletableFuture.completedFuture(RETCODE_NODESERVICE_NULL);
        if (message instanceof CompletableFuture) {
            return ((CompletableFuture) message).thenCompose((json) -> _engine.node.sendMessage(recent, json, last, groupids));
        }
        CompletableFuture<Integer> rs = _engine.node.sendMessage(recent, message, last, groupids);
        if (_engine.finest) _engine.logger.finest("wsgroupid:" + Arrays.toString(groupids) + " " + (recent ? "recent " : "") + "send websocket result is " + rs + " on " + this + " by message(" + _jsonConvert.convertTo(message) + ")");
        return rs;
    }

    /**
     * 广播消息， 给所有人的所有接入的WebSocket节点发消息
     *
     * @param message 消息内容
     *
     * @return 为0表示成功， 其他值表示部分发送异常
     */
    public final CompletableFuture<Integer> broadcastEachMessage(final Object message) {
        return broadcastMessage(false, message, true);
    }

    /**
     * 广播消息， 给所有人最近接入的WebSocket节点发消息
     *
     * @param message 消息内容
     *
     * @return 为0表示成功， 其他值表示部分发送异常
     */
    public final CompletableFuture<Integer> broadcastRecentMessage(final Object message) {
        return broadcastMessage(true, message, true);
    }

    /**
     * 广播消息， 给所有人发消息
     *
     * @param recent  是否只发送给最近接入的WebSocket节点
     * @param message 消息内容
     *
     * @return 为0表示成功， 其他值表示部分发送异常
     */
    public final CompletableFuture<Integer> broadcastMessage(final boolean recent, final Object message) {
        return broadcastMessage(recent, message, true);
    }

    /**
     * 广播消息， 给所有人发消息
     *
     * @param recent  是否只发送给最近接入的WebSocket节点
     * @param message 消息内容
     * @param last    是否最后一条
     *
     * @return 为0表示成功， 其他值表示部分发送异常
     */
    public final CompletableFuture<Integer> broadcastMessage(final boolean recent, final Object message, final boolean last) {
        if (_engine.node == null) return CompletableFuture.completedFuture(RETCODE_NODESERVICE_NULL);
        if (message instanceof CompletableFuture) {
            return ((CompletableFuture) message).thenCompose((json) -> _engine.node.broadcastMessage(recent, json, last));
        }
        CompletableFuture<Integer> rs = _engine.node.broadcastMessage(recent, message, last);
        if (_engine.finest) _engine.logger.finest("broadcast " + (recent ? "recent " : "") + "send websocket result is " + rs + " on " + this + " by message(" + _jsonConvert.convertTo(message) + ")");
        return rs;
    }

    /**
     * 获取用户在线的SNCP节点地址列表，不是分布式则返回元素数量为1，且元素值为null的列表<br>
     * InetSocketAddress 为 SNCP节点地址
     *
     * @param groupid Serializable
     *
     * @return 地址列表
     */
    public CompletableFuture<Collection<InetSocketAddress>> getRpcNodeAddresses(final Serializable groupid) {
        if (_engine.node == null) return CompletableFuture.completedFuture(null);
        return _engine.node.getRpcNodeAddresses(groupid);
    }

    /**
     * 获取在线用户的详细连接信息 <br>
     * Map.key 为 SNCP节点地址, 含值为null的key表示没有分布式
     * Map.value 为 用户客户端的IP
     *
     * @param groupid Serializable
     *
     * @return 地址集合
     */
    public CompletableFuture<Map<InetSocketAddress, List<String>>> getRpcNodeWebSocketAddresses(final Serializable groupid) {
        if (_engine.node == null) return CompletableFuture.completedFuture(null);
        return _engine.node.getRpcNodeWebSocketAddresses(groupid);
    }

    /**
     * 获取当前WebSocket下的属性，非线程安全
     *
     * @param <T>  属性值的类型
     * @param name 属性名
     *
     * @return 属性值
     */
    @SuppressWarnings("unchecked")
    public final <T> T getAttribute(String name) {
        return attributes == null ? null : (T) attributes.get(name);
    }

    /**
     * 移出当前WebSocket下的属性，非线程安全
     *
     * @param <T>  属性值的类型
     * @param name 属性名
     *
     * @return 属性值
     */
    public final <T> T removeAttribute(String name) {
        return attributes == null ? null : (T) attributes.remove(name);
    }

    /**
     * 给当前WebSocket下的增加属性，非线程安全
     *
     * @param name  属性值
     * @param value 属性值
     */
    public final void setAttribute(String name, Object value) {
        if (attributes == null) attributes = new HashMap<>();
        attributes.put(name, value);
    }

    /**
     * 获取当前WebSocket所属的groupid
     *
     * @return groupid
     */
    public final G getGroupid() {
        return _groupid;
    }

    /**
     * 获取当前WebSocket的会话ID， 不会为null
     *
     * @return sessionid
     */
    public final String getSessionid() {
        return _sessionid;
    }

    /**
     * 获取客户端直接地址, 当WebSocket连接是由代理服务器转发的，则该值固定为代理服务器的IP地址
     *
     * @return SocketAddress
     */
    public final SocketAddress getRemoteAddress() {
        return _remoteAddress;
    }

    /**
     * 获取客户端真实地址 同 HttpRequest.getRemoteAddr()
     *
     * @return String
     */
    public final String getRemoteAddr() {
        return _remoteAddr;
    }

    //-------------------------------------------------------------------
    /**
     * 获取当前WebSocket所属的WebSocketGroup， 不会为null
     *
     * @return WebSocketGroup
     */
    protected final WebSocketGroup getWebSocketGroup() {
        return _group;
    }

    /**
     * 获取指定groupid的WebSocketGroup, 没有返回null
     *
     * @param groupid Serializable
     *
     * @return WebSocketGroup
     */
    protected final WebSocketGroup getWebSocketGroup(G groupid) {
        return _engine.getWebSocketGroup(groupid);
    }

    /**
     * 获取当前进程节点所有在线的WebSocketGroup
     *
     * @return WebSocketGroup列表
     */
    protected final Collection<WebSocketGroup> getWebSocketGroups() {
        return _engine.getWebSocketGroups();
    }

    //-------------------------------------------------------------------
    /**
     * 返回sessionid, null表示连接不合法或异常,默认实现是request.getSessionid(true)，通常需要重写该方法
     *
     * @param request HttpRequest
     *
     * @return sessionid
     */
    protected CompletableFuture<String> onOpen(final HttpRequest request) {
        return CompletableFuture.completedFuture(request.getSessionid(true));
    }

    /**
     * 创建groupid， null表示异常， 必须实现该方法， 通常为用户ID为groupid
     *
     * @return groupid
     */
    protected abstract CompletableFuture<G> createGroupid();

    /**
     * 标记为WebSocketBinary才需要重写此方法
     *
     * @param channel 请求连接
     */
    public void onRead(AsyncConnection channel) {
    }

    /**
     * WebSokcet连接成功后的回调方法
     */
    public void onConnected() {
    }

    /**
     * ping后的回调方法
     *
     * @param bytes 数据
     */
    public void onPing(byte[] bytes) {
    }

    /**
     * pong后的回调方法
     *
     * @param bytes 数据
     */
    public void onPong(byte[] bytes) {
    }

    /**
     * 接收到消息的回调方法
     *
     * @param message 消息
     * @param last    是否最后一条
     */
    public void onMessage(T message, boolean last) {
    }

    /**
     * 接收到二进制消息的回调方法
     *
     * @param bytes 消息
     * @param last  是否最后一条
     */
    public void onMessage(byte[] bytes, boolean last) {
    }

    /**
     * 关闭的回调方法，调用此方法时WebSocket已经被关闭
     *
     * @param code   结果码，非0表示非正常关闭
     * @param reason 关闭原因
     */
    public void onClose(int code, String reason) {
    }

    /**
     * 获取最后一次发送消息的时间
     *
     * @return long
     */
    public long getLastSendTime() {
        return this._runner == null ? 0 : this._runner.lastSendTime;
    }

    /**
     * 显式地关闭WebSocket
     */
    public final void close() {
        if (this._runner != null) this._runner.closeRunner();
    }

    @Override
    public String toString() {
        return this.websocketid + "@" + _remoteAddr;
    }
}
