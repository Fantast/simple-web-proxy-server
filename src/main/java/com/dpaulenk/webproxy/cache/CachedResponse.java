package com.dpaulenk.webproxy.cache;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public class CachedResponse extends DefaultFullHttpResponse {

    private final int contentSize;

    private long birthTime;

    private long maxAge;

    private long lastModified;

    public CachedResponse(HttpVersion version, HttpResponseStatus status) {
        super(version, status);
        contentSize = 0;
    }

    public CachedResponse(HttpVersion version, HttpResponseStatus status, ByteBuf content) {
        super(version, status, content);
        contentSize = content.readableBytes();
    }

    public void setBirthTime(long birthTime) {
        this.birthTime = birthTime;
    }

    public void setMaxAge(long maxAge) {
        this.maxAge = maxAge;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public long getLastModified() {
        return lastModified;
    }

    public int getContentSize() {
        return contentSize;
    }

    public long currentAge() {
        return System.currentTimeMillis() - birthTime;
    }

    public boolean expired(long maxAge) {
        long currentAge = currentAge();
        return currentAge >= maxAge || currentAge >= this.maxAge;
    }

    public boolean modifiedSince(long modifiedSince) {
        return lastModified > 0 && lastModified > modifiedSince;
    }
}
