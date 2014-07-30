package com.dpaulenk.webproxy.inbound;

import com.dpaulenk.webproxy.WebProxyServer;
import com.dpaulenk.webproxy.common.AbstractProxyHandler;
import com.dpaulenk.webproxy.outbound.OutboundInitializer;
import com.dpaulenk.webproxy.outbound.OutboundProxyHandler;
import com.dpaulenk.webproxy.utils.ProxyUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import org.apache.log4j.Logger;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static com.dpaulenk.webproxy.inbound.InboundHandlerState.*;
import static com.dpaulenk.webproxy.utils.ProxyUtils.*;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;

public class InboundProxyHandler extends AbstractProxyHandler<HttpRequest, InboundHandlerState> {
    private static final Logger logger = Logger.getLogger(OutboundProxyHandler.class);

    private final WebProxyServer proxyServer;

    private OutboundProxyHandler outboundHandler;

    public InboundProxyHandler(WebProxyServer proxyServer) {
        this.proxyServer = proxyServer;
        setCurrentState(INITIAL);
    }

    @Override
    protected void channelReadBytes(ChannelHandlerContext ctx, ByteBuf msg) {
        assert outboundHandler != null;
        outboundHandler.writeToChannel(msg);
    }

    List<HttpObject> missedChunks = new ArrayList<HttpObject>();

    @Override
    protected void channelReadHttpObject(ChannelHandlerContext ctx, HttpObject msg) {
        switch (currentState) {
            case INITIAL:
                readInitialRequest((HttpRequest)msg);
                break;
            case WAITING_OUTBOUND_CONNECTION:
            case WAITING_ESTEBLISHED_RESPONSE:
                ReferenceCountUtil.retain(msg);
                missedChunks.add(msg);
                //we stop reading, when connecting to remote server
//                throw new IllegalStateException("Shouldn't happen");
                break;
            case READING_CONTENT:
                readNextContent(msg);
                break;
            case DISCONNECTED:
                //just ignore everything
                break;
        }
    }

    private void purgeMissedChunks() {
        for (HttpObject missed : missedChunks) {
            channelReadHttpObject(ctx, missed);
            ReferenceCountUtil.release(missed);
        }
        missedChunks.clear();
    }

    private void readNextContent(HttpObject msg) {
        outboundHandler.writeToChannel(msg);

        if (msg instanceof LastHttpContent) {
            setCurrentState(INITIAL);
        }
    }

    private void readInitialRequest(HttpRequest req) {
        req = copyRequest(req);

        String hostAndPort = ProxyUtils.getHostAndPort(req);
        if (hostAndPort == null || hostAndPort.isEmpty()) {
            writeBadRequestResponse("Missing hostAndPort in request to: " + req.getUri());
            setCurrentState(DISCONNECTED);
            return;
        }

        if (isConnectRequest(req)) {
            if (outboundHandler != null) {
                writeBadRequestResponse("Can't reuse old connection for tunneling.");
                return;
            }
        }

        if (outboundHandler == null) {
            createOutboundHandler(req, hostAndPort);
            return;
        }

        prepareProxyRequest(req);
        outboundHandler.writeToChannel(req);

        if (req instanceof LastHttpContent) {
            setCurrentState(INITIAL);
        } else {
            setCurrentState(READING_CONTENT);
        }
    }

    private void createOutboundHandler(HttpRequest initialRequest, String hostAndPort) {
        setCurrentState(WAITING_OUTBOUND_CONNECTION);

        ReferenceCountUtil.retain(initialRequest);

        stopReading();

        outboundHandler = new OutboundProxyHandler(proxyServer, this, initialRequest);

        connectToRemoteServer(hostAndPort, initialRequest);
    }

    private void connectToRemoteServer(String hostAndPort, final HttpRequest initialRequest) {
        Bootstrap b =
            new Bootstrap()
                .group(proxyServer.getOutboundEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new OutboundInitializer(outboundHandler, isConnectRequest(initialRequest)));

        String remoteHost = hostAndPort;
        int remotePort = 80;

        int colonPos = hostAndPort.indexOf(":");
        if (colonPos != -1) {
            remoteHost = hostAndPort.substring(0, colonPos);
            remotePort = Integer.parseInt(hostAndPort.substring(colonPos + 1));
        }

        ChannelFuture connectFuture = b.connect(remoteHost, remotePort);
        connectFuture.addListener(new ConnectionFutureListener(initialRequest) {
            @Override
            protected void success() {
                if (isConnectRequest(initialRequest)) {
                    sendConnectionEstablished(initialRequest);
                } else {
                    remoteConnectionSucceded(initialRequest);
                }
            }
        });
    }

    private void remoteConnectionSucceded(HttpRequest initialRequest) {
        if (initialRequest != null) {
            prepareProxyRequest(initialRequest);
            outboundHandler.writeToChannel(initialRequest);

            //we retained, when starting a connection
            ReferenceCountUtil.release(initialRequest);

            if (initialRequest instanceof LastHttpContent) {
                setCurrentState(INITIAL);
            } else {
                setCurrentState(READING_CONTENT);
            }

            purgeMissedChunks();
        }
        startReading();
    }

    /**
     * http://curl.haxx.se/rfc/draft-luotonen-web-proxy-tunneling-01.txt
     */
    private void sendConnectionEstablished(HttpRequest initialRequest) {
        setCurrentState(WAITING_ESTEBLISHED_RESPONSE);

        DefaultFullHttpResponse res = simpleResponse(CONNECTION_ESTABLISHED, null);
        res.headers().set(HttpHeaders.Names.CONNECTION, "keep-alive");
        res.headers().set(PROXY_CONNECTION, "keep-alive");
        ProxyUtils.addViaHeader(res);

        writeToChannel(res).addListener(new ConnectionFutureListener(initialRequest) {
            @Override
            protected void success() {
                setCurrentState(READING_CONTENT);

                missedChunks.clear();

                setupTunneling();
                startReading();
            }
        });
    }

    private void setupTunneling() {
        channel.pipeline().remove("httpcodec");
    }

    private void remoteConnectionFailed(HttpRequest initialRequest) {
        writeBadGateway(initialRequest);
    }

    private void writeBadGateway(HttpRequest request) {
        logger.info("Sending Bad Gateway: " + request.getUri());

        DefaultFullHttpResponse res = simpleResponse(BAD_GATEWAY, "Bad Gateway: " + request.getUri());
        res.headers().set(HttpHeaders.Names.CONNECTION, "close");
        writeToChannel(res);
        disconnect();
    }

    private void writeBadRequestResponse(String message) {
        logger.info("Sending Bad Request: " + message);

        DefaultFullHttpResponse res = simpleResponse(BAD_REQUEST, message);
        res.headers().set(HttpHeaders.Names.CONNECTION, "close");
        writeToChannel(res);
        disconnect();
    }

    private DefaultFullHttpResponse simpleResponse(HttpResponseStatus status, String body) {
        if (body == null) {
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        } else {
            byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
            ByteBuf buf = Unpooled.copiedBuffer(bytes);

            DefaultFullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
            res.headers().set(HttpHeaders.Names.CONTENT_LENGTH, bytes.length);
            res.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/html; charset=UTF-8");

            return res;
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Error in inbound handler: ", cause);
        if (outboundHandler != null) {
            outboundHandler.disconnect();
        }
        disconnect();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (outboundHandler != null) {
            outboundHandler.disconnect();
        }
        disconnect();
    }

    private abstract class ConnectionFutureListener implements ChannelFutureListener {
        private HttpRequest initialRequest;

        public ConnectionFutureListener(HttpRequest initialRequest) {
            this.initialRequest = initialRequest;
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
                success();
            } else {
                remoteConnectionFailed(initialRequest);
            }
        }

        protected abstract void success();
    }
}