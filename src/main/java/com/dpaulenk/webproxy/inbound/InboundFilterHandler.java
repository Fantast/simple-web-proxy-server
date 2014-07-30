package com.dpaulenk.webproxy.inbound;

import com.dpaulenk.webproxy.cache.CachedResponse;
import com.dpaulenk.webproxy.utils.ProxyUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.apache.log4j.Logger;

import java.util.regex.Pattern;

import static com.dpaulenk.webproxy.utils.ProxyUtils.simpleResponse;

public class InboundFilterHandler extends SimpleChannelInboundHandler<HttpObject> {
    private static final Logger logger = Logger.getLogger(InboundFilterHandler.class);

    private final Pattern[] blackList;

    private boolean blockingRequest;

    public InboundFilterHandler(String[] blackList) {
        this.blackList = new Pattern[blackList.length];
        for (int i = 0; i < blackList.length; i++) {
            try {
                this.blackList[i] = Pattern.compile(blackList[i]);
            } catch (Exception e) {
                //ignore mailformed patterns
                this.blackList[i] = null;
            }
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject obj) throws Exception {
        if (obj instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) obj;
            if (shouldBlock(req)) {
                logger.info("Blocking black-listed request: " + req.getUri());
                sendForbidden(ctx, req);
                blockingRequest = true;
            } else {
                ctx.fireChannelRead(obj);
            }
        }

        if (obj instanceof HttpContent && blockingRequest) {
            if (obj instanceof LastHttpContent) {
                blockingRequest = false;
            }
            return;
        }

        ctx.fireChannelRead(obj);
    }

    private void sendForbidden(ChannelHandlerContext ctx, HttpRequest req) {
        ctx.writeAndFlush(
            simpleResponse(HttpResponseStatus.FORBIDDEN, req.getUri() + " is black-listed.")
        );
    }

    private boolean shouldBlock(HttpRequest req) {
        String uri = req.getUri();
        for (Pattern blockPattern : blackList) {
            if (blockPattern != null && blockPattern.matcher(uri).matches()) {
                return true;
            }
        }
        return false;
    }
}
