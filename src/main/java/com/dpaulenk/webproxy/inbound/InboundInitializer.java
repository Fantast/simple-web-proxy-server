package com.dpaulenk.webproxy.inbound;

import com.dpaulenk.webproxy.WebProxyServer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class InboundInitializer extends ChannelInitializer<SocketChannel> {
    private final WebProxyServer proxyServer;

    public InboundInitializer(WebProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline p = ch.pipeline();

        p.addLast("httpcodec", new HttpServerCodec(8192, 8192 * 2, 8192 * 2));

        p.addLast(new InboundCacheHandler());
        p.addLast(new InboundProxyHandler(proxyServer));
    }
}
