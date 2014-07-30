package com.dpaulenk.webproxy.inbound;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;

public class InboundFilterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (!intercept(ctx, req)) {
            ctx.fireChannelRead(req);
        }
    }

    private boolean intercept(ChannelHandlerContext ctx, FullHttpRequest req) {
        //todo:
        return true;
    }
}
