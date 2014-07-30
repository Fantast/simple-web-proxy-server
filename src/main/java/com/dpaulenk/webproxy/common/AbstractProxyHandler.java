package com.dpaulenk.webproxy.common;

import com.dpaulenk.webproxy.inbound.InboundHandlerState;
import com.dpaulenk.webproxy.utils.ChannelUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

public abstract class AbstractProxyHandler<R extends HttpMessage, S> extends SimpleChannelInboundHandler<Object> {

    protected volatile ChannelHandlerContext ctx;
    protected volatile Channel channel;

    protected S currentState;

    protected boolean tunneling = false;

    public S getCurrentState() {
        return currentState;
    }

    public void setCurrentState(S currentState) {
        this.currentState = currentState;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpObject) {
            channelReadHttpObject(ctx, (HttpObject) msg);
        } else {
            channelReadBytes(ctx, (ByteBuf) msg);
        }
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        this.channel = ctx.channel();
        super.channelRegistered(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        disconnect();
    }

    protected abstract void channelReadBytes(ChannelHandlerContext ctx, ByteBuf msg);

    protected abstract void channelReadHttpObject(ChannelHandlerContext ctx, HttpObject msg);

    public ChannelFuture writeToChannel(Object msg) {
        ReferenceCountUtil.retain(msg);
        return channel.writeAndFlush(msg);
    }

    public void disconnect() {
        if (channel != null) {
            ChannelUtils.closeOnFlush(channel);
        }
    }

    public void stopReading() {
        channel.config().setAutoRead(false);
    }

    public void startReading() {
        channel.config().setAutoRead(true);
    }
}
