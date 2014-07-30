package com.dpaulenk.webproxy.inbound;

import com.dpaulenk.webproxy.WebProxyServer;
import com.dpaulenk.webproxy.cache.CachedResponse;
import com.dpaulenk.webproxy.cache.ResponseCache;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.apache.log4j.Logger;

import java.util.*;

import static com.dpaulenk.webproxy.utils.ProxyUtils.*;
import static io.netty.handler.codec.http.HttpHeaders.Names.*;

public class InboundCacheHandler extends ChannelDuplexHandler {
    private static final Logger logger = Logger.getLogger(InboundCacheHandler.class);

    private final ResponseCache responseCache;

    private final Queue<HttpRequest> requestsQueue = new LinkedList<HttpRequest>();

    private final int maxCachedResponseSize;
    private final int maxCumulationBufferComponents;

    private boolean servingFromCache;

    private HttpRequest currentRequest;
    private HttpResponse currentResponse;

    private int currentContentLength;
    private boolean isCachable;

    private final List<HttpContent> currentResponseChunks = new ArrayList<HttpContent>();

    private boolean passThrough = false;

    public InboundCacheHandler(WebProxyServer proxyServer) {
        responseCache = proxyServer.getResponseCache();
        maxCachedResponseSize = proxyServer.options().getMaxCachedResponseSize();
        maxCumulationBufferComponents = proxyServer.options().getMaxCumulationBufferComponents();
    }

    public void setPassThrough(boolean passThrough) {
        this.passThrough = passThrough;
    }

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
        if (passThrough || msg instanceof CachedResponse) {
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
            if (currentContentLength > maxCachedResponseSize) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Skip caching " + currentRequest.getUri() + "" +
                                 ", reason: response length exceeds Maximum Size of : " + maxCachedResponseSize + " bytes");
                }

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
            currentContentLength = 0;
            currentResponseChunks.clear();
            currentRequest = null;
            currentResponse = null;
        }

        super.write(ctx, msg, promise);
    }

    private void updateFromNonCachableResponse(HttpRequest currentRequest, HttpResponse currentResponse) {
        //update from 304 NOT MODIFIED responses
        CachedResponse cached = responseCache.get(currentRequest.getUri());
        if (currentResponse.getStatus().code() == 304) {
            long lastModified = determineLastModified(currentResponse);
            if (lastModified > 0) {
                if (cached != null) {
                    if (cached.getLastModified() < lastModified) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Removing " + currentRequest.getUri() + " - reason: expired Last-Modified");
                        }

                        removeFromCache(currentRequest, cached);
                    }
                }
            }
        } else if (cached != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Removing " + currentRequest.getUri() + " - reason: non-cashable request received");
            }
            removeFromCache(currentRequest, cached);
        }
    }

    /**
     * http://tools.ietf.org/html/rfc2616#section-13.4
     */
    private boolean isCachable(HttpRequest currentRequest, HttpResponse currentResponse) {
        //only cache responses with 200 OK status
        String uri = currentRequest.getUri();
        if (currentResponse.getStatus().code() != 200) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skip caching " + uri + ", reason: response status code: " + currentResponse.getStatus().code());
            }
            return false;
        }

        //only cache GET requests
        if (!HttpMethod.GET.equals(currentRequest.getMethod())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skip caching " + uri + ", reason: request http method: " + currentRequest.getMethod());
            }
            return false;
        }

        // http://tools.ietf.org/html/rfc2616#section-13.4
        //  "If there is neither a cache validator nor an explicit expiration
        //   time associated with a response, we do not expect it to be cached"
        if (!currentRequest.headers().contains(ETAG) && determineMaxAge(currentResponse) == -1) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skip caching " + uri + ", reason: request has no explicit expiration time");
            }
            return false;
        }

        // http://tools.ietf.org/html/rfc2616#section-14.9
        if (hasCacheControlValues(currentRequest, "no-cache", "no-store", "max-age=0")) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skip caching " + uri + ", reason: request has Cache-Control: " +
                             currentResponse.headers().getAll(CACHE_CONTROL));
            }
            return false;
        }
        if (hasCacheControlValues(currentResponse, "private", "no-cache", "max-age=0", "must-revalidate")) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skip caching " + uri + ", reason: response has Cache-Control: " +
                             currentResponse.headers().getAll(CACHE_CONTROL));
            }
            return false;
        }

        return true;
    }

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

        responseCache.put(uri, cached);
    }

    private CachedResponse mergedResponse(HttpResponse currentResponse, int currentContentLength, List<HttpContent> chunks) {
        CompositeByteBuf content = Unpooled.compositeBuffer(maxCumulationBufferComponents);

        int contetLength = 0;
        for (HttpContent chunk : chunks) {
            contetLength += chunk.content().readableBytes();
            content.addComponent(chunk.content());
        }
        content.writerIndex(content.writerIndex() + contetLength);

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

                CachedResponse cached = responseCache.get(req.getUri());
                if (cached != null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Removing " + req.getUri() + " - reason: modification request: " + method);
                    }
                    removeFromCache(req, cached);
                }
            }

            return null;
        }

        // http://tools.ietf.org/html/rfc2616#section-14.9
        if (hasCacheControlValues(req, "no-cache", "no-store", "max-age=0")) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skip lookup for " + req.getUri() +
                             " - reason: Control-Cache: " + req.headers().getAll(CACHE_CONTROL));
            }
            return null;
        }

        String uri = req.getUri();

        CachedResponse cachedResponse = responseCache.get(uri);
        if (cachedResponse == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("No cached entry for " + req.getUri());
            }
            return null;
        }

        long age = determineMaxAge(req);
        if (age != -1 && cachedResponse.expired(age)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Cached response expired for " + req.getUri() +
                             "; req.Cached-Control: " + req.headers().getAll(CACHE_CONTROL) +
                             "; resp.currentAge:" + cachedResponse.currentAge());
            }
            return null;
        }

        Date ifModifiedSince = parseDate(req.headers().get(IF_MODIFIED_SINCE));
        if (ifModifiedSince != null) {
            if (!cachedResponse.modifiedSince(ifModifiedSince.getTime())) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Sending '304 Not Modifed' for " + uri);
                }
                return notModifiedResponse(cachedResponse);
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Cached response is modifed since: " + req.headers().get(IF_MODIFIED_SINCE));
            }

            return null;
        }

        return cachedResponse;
    }

    private HttpResponse notModifiedResponse(HttpResponse original) {
        CachedResponse response = new CachedResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
        response.headers().add(original.headers());
        return response;
    }

    private void removeFromCache(HttpRequest currentRequest, CachedResponse expected) {
        responseCache.remove(currentRequest.getUri(), expected);
    }
}
