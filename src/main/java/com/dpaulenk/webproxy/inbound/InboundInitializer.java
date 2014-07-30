package com.dpaulenk.webproxy.inbound;

import com.dpaulenk.webproxy.WebProxyOptions;
import com.dpaulenk.webproxy.WebProxyServer;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;

public class InboundInitializer extends ChannelInitializer<SocketChannel> {
    private final WebProxyServer proxyServer;
    private final int maxChunkSize;
    private final boolean cachingEnabled;
    private final String[] blackList;

    public InboundInitializer(WebProxyServer proxyServer) {
        this.proxyServer = proxyServer;
        maxChunkSize = proxyServer.options().maxChunkSize();
        cachingEnabled = proxyServer.options().cachingEnabled();
        blackList = proxyServer.options().blackList();
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline p = ch.pipeline();

        p.addLast("httpcodec", new HttpServerCodec(8192, 8192 * 2, maxChunkSize));

        if (blackList != null && blackList.length > 0) {
            p.addLast("filter", new InboundFilterHandler(blackList));
        }

        if (cachingEnabled) {
            p.addLast("caching", new InboundCacheHandler(proxyServer));
        }
        p.addLast("proxy", new InboundProxyHandler(proxyServer));
    }
}
