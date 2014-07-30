package com.dpaulenk.webproxy.inbound;

import com.dpaulenk.webproxy.cache.CachedResponse;
import com.dpaulenk.webproxy.utils.ProxyUtils;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.dpaulenk.webproxy.utils.ProxyUtils.*;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;

public class InboundCacheHandler extends ChannelDuplexHandler {
    private static final Logger logger = Logger.getLogger(InboundCacheHandler.class);

    private final Queue<HttpRequest> requestsQueue = new LinkedList<HttpRequest>();

    private final int maxCachedContentSize = 8192 * 4; //todo: configurable
    private final int maxCumulationBufferComponents = 1024; //todo: configurable

    private boolean servingFromCache;

    private HttpRequest currentRequest;
    private HttpResponse currentResponse;

    private int currentContentLength;
    private boolean isCachable;

    private List<HttpContent> currentResponseChunks = new ArrayList<HttpContent>();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            servingFromCache = false;

            HttpRequest req = (HttpRequest) msg;

            ReferenceCountUtil.retain(req);

            requestsQueue.add(req);

            HttpResponse response = cachedResponse(req);
            if (response != null) {
                logger.info("Serving response from cache for uri: " + req.getUri());
                servingFromCache = true;
                ctx.channel().writeAndFlush(response);
                return;
            }
        }

        if (servingFromCache && msg instanceof HttpContent) {
            return;
        }

        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof CachedResponse) {
            super.write(ctx, msg, promise);
            return;
        }

        if (msg instanceof HttpResponse) {
            currentRequest = requestsQueue.poll();
            currentResponse = (HttpResponse) msg;
            currentContentLength = 0;

            isCachable = isCachable(currentRequest, currentResponse);

            ReferenceCountUtil.retain(currentResponse);
        }

        if (isCachable && msg instanceof HttpContent) {
            if (!(msg instanceof HttpResponse)) {
                ReferenceCountUtil.retain(msg);
            }

            HttpContent content = (HttpContent) msg;

            currentContentLength += content.content().readableBytes();
            if (currentContentLength > maxCachedContentSize) {
                isCachable = false;
                currentResponseChunks.clear();
            } else {
                currentResponseChunks.add(content);
            }
        }

        if (msg instanceof LastHttpContent) {
            if (isCachable) {
                cacheResponse(currentRequest, currentResponse, currentContentLength, currentResponseChunks);
            } else {
                updateFromNonCachableResponse(currentRequest, currentResponse);
            }

            ReferenceCountUtil.release(currentResponse);
            ReferenceCountUtil.release(currentRequest);

            isCachable = false;
            currentResponseChunks.clear();
            currentRequest = null;
            currentResponse = null;
        }

        super.write(ctx, msg, promise);
    }

    private void updateFromNonCachableResponse(HttpRequest currentRequest, HttpResponse currentResponse) {
        //update from 304 NOT MODIFIED responses
        if (currentResponse.getStatus().code() == 304) {
            long lastModified = determineLastModified(currentResponse);
            if (lastModified > 0) {
                CachedResponse cached = cache.get(currentRequest.getUri());
                if (cached != null) {
                    if (cached.getLastModified() < lastModified) {
                        //todo: syncronize properly
                        removeFromCache(currentRequest);
                    }
                }
            }
        }
    }

    /**
     * http://tools.ietf.org/html/rfc2616#section-13.4
     */
    private boolean isCachable(HttpRequest currentRequest, HttpResponse currentResponse) {
        //only cache responses with 200 OK status
        if (currentResponse.getStatus().code() != 200) {
            return false;
        }

        //only cache GET requests
        if (!HttpMethod.GET.equals(currentRequest.getMethod())) {
            return false;
        }

        // http://tools.ietf.org/html/rfc2616#section-13.4
        //  "If there is neither a cache validator nor an explicit expiration
        //   time associated with a response, we do not expect it to be cached"
        if (!currentRequest.headers().contains(ETAG) && determineMaxAge(currentResponse) == -1) {
            return false;
        }

        // http://tools.ietf.org/html/rfc2616#section-14.9
        if (hasCacheControlValues(currentRequest, "no-cache", "no-store", "max-age=0")) {
            return false;
        }
        if (hasCacheControlValues(currentRequest, "private", "no-cache", "max-age=0", "must-revalidate")) {
            return false;
        }

        return true;
    }

    static Map<String, CachedResponse> cache = new ConcurrentHashMap<String, CachedResponse>();

    private void cacheResponse(HttpRequest currentRequest, HttpResponse currentResponse,
                               int currentContentLength, List<HttpContent> currentResponseChunks) {
        logger.info("Caching response for uri: " + currentRequest.getUri());

        String uri = currentRequest.getUri();

        CachedResponse cached = mergedResponse(currentResponse, currentContentLength, currentResponseChunks);

        long currentTime = System.currentTimeMillis();
        long maxAge = determineMaxAge(currentTime, currentResponse);
        long lastModified = determineLastModified(currentResponse);

        cached.setBirthTime(currentTime);
        cached.setMaxAge(maxAge);
        cached.setLastModified(lastModified);

        cache.put(uri, cached);
    }

    private CachedResponse mergedResponse(HttpResponse currentResponse, int currentContentLength, List<HttpContent> chunks) {
        CompositeByteBuf content = Unpooled.compositeBuffer(maxCumulationBufferComponents);

        for (HttpContent chunk : chunks) {
            content.addComponent(chunk.content());
        }
        content.writerIndex(currentContentLength);

        CachedResponse res =
            new CachedResponse(currentResponse.getProtocolVersion(), currentResponse.getStatus(), content);

        assert !chunks.isEmpty();

        LastHttpContent lastChunk = (LastHttpContent) chunks.get(chunks.size() - 1);

        res.headers().add(currentResponse.headers());
        res.headers().add(lastChunk.trailingHeaders());
        res.headers().add(CONTENT_LENGTH, currentContentLength);

        ReferenceCountUtil.retain(res);

        return res;
    }

    private HttpResponse cachedResponse(HttpRequest req) {
        //only cache GET requests
        HttpMethod method = req.getMethod();
        if (!HttpMethod.GET.equals(method)) {
            // resource might be modified: http://tools.ietf.org/html/rfc2616#section-13.10
            if (HttpMethod.PUT.equals(method) ||
                HttpMethod.DELETE.equals(method) ||
                HttpMethod.POST.equals(method)) {
                removeFromCache(req);
            }

            return null;
        }

        // http://tools.ietf.org/html/rfc2616#section-14.9
        if (hasCacheControlValues(req, "no-cache", "no-store", "max-age=0")) {
            return null;
        }

        String uri = req.getUri();

        CachedResponse cachedResponse = cache.get(uri);
        if (cachedResponse == null) {
            return null;
        }

        long age = determineMaxAge(req);
        if (age != -1 && cachedResponse.expired(age)) {
            return null;
        }

        Date ifModifiedSince = parseDate(req.headers().get(IF_MODIFIED_SINCE));
        if (ifModifiedSince != null) {
            if (!cachedResponse.modifiedSince(ifModifiedSince.getTime())) {
                return notModifiedResponse(cachedResponse);
            }
            return null;
        }

        return cache.get(uri);
    }

    private HttpResponse notModifiedResponse(HttpResponse original) {
        CachedResponse response = new CachedResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
        response.headers().add(original.headers());
        return response;
    }

    private void removeFromCache(HttpRequest currentRequest) {
        //todo:

    }
}
