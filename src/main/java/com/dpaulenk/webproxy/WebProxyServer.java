package com.dpaulenk.webproxy;

import com.dpaulenk.webproxy.inbound.InboundInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class WebProxyServer {
    public final int port;

    public EventLoopGroup outboundEventLoopGroup;

    public WebProxyServer(int port) {
        this.port = port;
    }

    public void start() {
        EventLoopGroup serverGroup = new NioEventLoopGroup(1);
        EventLoopGroup inboundGroup = new NioEventLoopGroup(8);

        outboundEventLoopGroup = new NioEventLoopGroup(8);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(serverGroup, inboundGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 100)
                    .childHandler(new InboundInitializer(this));

            ChannelFuture f = b.bind(port).sync();

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            //todo: handle properly
            e.printStackTrace();
        } finally {
            // Shut down all event loops to terminate all threads.
            serverGroup.shutdownGracefully();
            inboundGroup.shutdownGracefully();
        }
    }

    public EventLoopGroup getOutboundEventLoopGroup() {
        return outboundEventLoopGroup;
    }
}
