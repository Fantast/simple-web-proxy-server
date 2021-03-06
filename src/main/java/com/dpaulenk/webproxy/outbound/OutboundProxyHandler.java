package com.dpaulenk.webproxy.outbound;

import com.dpaulenk.webproxy.common.AbstractProxyHandler;
import com.dpaulenk.webproxy.inbound.InboundProxyHandler;
import com.dpaulenk.webproxy.utils.ProxyUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.apache.log4j.Logger;

import static com.dpaulenk.webproxy.outbound.OutboundHandlerState.DISCONNECTED;
import static com.dpaulenk.webproxy.outbound.OutboundHandlerState.INITIAL;
import static com.dpaulenk.webproxy.outbound.OutboundHandlerState.READING_CONTENT;

public class OutboundProxyHandler extends AbstractProxyHandler<HttpResponse, OutboundHandlerState> {

    private static final Logger logger = Logger.getLogger(OutboundProxyHandler.class);

    private final InboundProxyHandler inboundHandler;

    private boolean isKeepAlive = true;

    public OutboundProxyHandler(InboundProxyHandler inboundHandler) {
        this.inboundHandler = inboundHandler;
        setCurrentState(INITIAL);
    }

    @Override
    protected void channelReadBytes(ChannelHandlerContext ctx, ByteBuf msg) {
        inboundHandler.writeToChannel(msg);
    }

    @Override
    protected void channelReadHttpObject(ChannelHandlerContext ctx, HttpObject msg) {
        switch (currentState) {
            case INITIAL:
                reaInitialResponse((HttpResponse) msg);
                break;
            case READING_CONTENT:
                readNextContent(ctx, msg);
                break;
            case DISCONNECTED:
                break;
        }
    }

    private void reaInitialResponse(HttpResponse res) {
        ProxyUtils.prepareProxyResponse(res);

        isKeepAlive = isKeepAlive && HttpHeaders.isKeepAlive(res);

        inboundHandler.writeToChannel(res);

        if (res instanceof LastHttpContent) {
            onLastChunkWritten();
        } else {
            setCurrentState(READING_CONTENT);
        }
    }

    private void readNextContent(ChannelHandlerContext ctx, HttpObject msg) {
        inboundHandler.writeToChannel(msg);

        if (msg instanceof LastHttpContent) {
            onLastChunkWritten();
        }
    }

    private void onLastChunkWritten() {
        setCurrentState(INITIAL);
        inboundHandler.writeToChannel(Unpooled.EMPTY_BUFFER);

        if (!isKeepAlive) {
            forceDisconnect();
        }
    }

    private void forceDisconnect() {
        disconnect();
        inboundHandler.disconnect();
        setCurrentState(DISCONNECTED);
    }

    @Override
    public ChannelFuture writeToChannel(Object msg) {
        if (msg instanceof HttpRequest) {
            isKeepAlive = isKeepAlive && HttpHeaders.isKeepAlive((HttpMessage) msg);
        }
        return super.writeToChannel(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("Error in outbound handler: ", cause);
        forceDisconnect();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        forceDisconnect();
    }
}
