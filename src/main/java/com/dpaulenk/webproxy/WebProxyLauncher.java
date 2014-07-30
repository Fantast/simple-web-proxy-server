package com.dpaulenk.webproxy;

import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Log4JLoggerFactory;

public class WebProxyLauncher {
    public static void main(String[] args) {
        InternalLoggerFactory.setDefaultFactory(new Log4JLoggerFactory());

        //todo: configuration
        new WebProxyServer(8181).start();
    }
}
