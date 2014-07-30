package com.dpaulenk.webproxy.outbound;

import com.dpaulenk.webproxy.WebProxyServer;
import com.dpaulenk.webproxy.common.AbstractProxyHandler;
import com.dpaulenk.webproxy.inbound.InboundProxyHandler;
import com.dpaulenk.webproxy.utils.ProxyUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.apache.log4j.Logger;

import static com.dpaulenk.webproxy.outbound.OutboundHandlerState.INITIAL;
import static com.dpaulenk.webproxy.outbound.OutboundHandlerState.READING_CONTENT;

public class OutboundProxyHandler extends AbstractProxyHandler<HttpResponse, OutboundHandlerState> {

    private static final Logger logger = Logger.getLogger(OutboundProxyHandler.class);

    private final InboundProxyHandler inboundHandler;
    private HttpRequest initialRequest;

    public OutboundProxyHandler(WebProxyServer proxyServer, InboundProxyHandler inboundHandler,
                                HttpRequest initialRequest) {
        this.inboundHandler = inboundHandler;
        this.initialRequest = initialRequest;
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

        inboundHandler.writeToChannel(res);

        if (res instanceof LastHttpContent) {
            setCurrentState(INITIAL);
            inboundHandler.writeToChannel(Unpooled.EMPTY_BUFFER);
        } else {
            setCurrentState(READING_CONTENT);
        }
    }

    private void readNextContent(ChannelHandlerContext ctx, HttpObject msg) {
        inboundHandler.writeToChannel(msg);

        if (msg instanceof LastHttpContent) {
            setCurrentState(INITIAL);
            inboundHandler.writeToChannel(Unpooled.EMPTY_BUFFER);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Error in inbound handler: ", cause);
        inboundHandler.disconnect();
        disconnect();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        inboundHandler.disconnect();
        disconnect();
    }
}
