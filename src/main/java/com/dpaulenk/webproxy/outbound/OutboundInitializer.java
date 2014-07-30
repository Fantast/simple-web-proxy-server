package com.dpaulenk.webproxy.outbound;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;

public class OutboundInitializer extends ChannelInitializer<SocketChannel> {
    private final OutboundProxyHandler outboundHandler;
    private final boolean isTunneling;
    private final int maxChunkSize;

    public OutboundInitializer(OutboundProxyHandler outboundHandler, boolean isTunneling, int maxChunkSize) {
        this.outboundHandler = outboundHandler;
        this.isTunneling = isTunneling;
        this.maxChunkSize = maxChunkSize;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline p = ch.pipeline();

        if (!isTunneling) {
            p.addLast("httpcodec", new HttpClientCodec(8192, 8192 * 2, maxChunkSize));
        }

        p.addLast(outboundHandler);
    }
}
