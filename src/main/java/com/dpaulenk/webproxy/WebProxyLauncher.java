package com.dpaulenk.webproxy;

import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Log4JLoggerFactory;

public class WebProxyLauncher {

    private static final String OPTIONS_FILE = "/simple-web-proxy.properties";

    public static void main(String[] args) {
        InternalLoggerFactory.setDefaultFactory(new Log4JLoggerFactory());

        //it shouldn't really be a singleton, but for now it's just simplier for it to be
        WebProxyOptions.getInstance().loadFromFile(OPTIONS_FILE);

        new WebProxyServer(WebProxyOptions.getInstance().listenPort()).start();
    }
}
