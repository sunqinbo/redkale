/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.util;

import java.net.*;

/**
 *
 * @author zhangjx
 */
public class RedkaleClassLoader extends URLClassLoader {

    public RedkaleClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    public Class<?> loadClass(String name, byte[] b) {
        return defineClass(name, b, 0, b.length);
    }

    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }

    @Override
    public URL[] getURLs() {
        return super.getURLs();
    }

}