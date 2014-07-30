package com.dpaulenk.webproxy;

import com.dpaulenk.webproxy.cache.ResponseCache;
import com.dpaulenk.webproxy.inbound.InboundInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class WebProxyServer {

    private final static WebProxyOptions options = WebProxyOptions.getInstance();

    private final int port;

    private final ResponseCache responseCache;

    private EventLoopGroup outboundEventLoopGroup;

    public WebProxyServer(int port) {
        this.responseCache = new ResponseCache(options);
        this.port = port;
    }

    public void start() {
        EventLoopGroup serverGroup = new NioEventLoopGroup(options.serverThreadsCount());
        EventLoopGroup inboundGroup = new NioEventLoopGroup(options.inboundThreadsCount());

        outboundEventLoopGroup = new NioEventLoopGroup(options.outboundThreadsCount());

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(serverGroup, inboundGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, options.maximumAwaitingAccept())
                    .childHandler(new InboundInitializer(this));

            ChannelFuture f = b.bind(port).sync();

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();
        } catch (InterruptedException ignore) {
        } finally {
            // Shut down all event loops to terminate all threads.
            serverGroup.shutdownGracefully();
            inboundGroup.shutdownGracefully();
        }
    }

    public EventLoopGroup getOutboundEventLoopGroup() {
        return outboundEventLoopGroup;
    }

    public ResponseCache getResponseCache() {
        return responseCache;
    }

    public WebProxyOptions options() {
        return options;
    }
}
