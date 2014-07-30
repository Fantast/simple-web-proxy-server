package com.dpaulenk.webproxy.cache;

import com.dpaulenk.webproxy.WebProxyOptions;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.googlecode.concurrentlinkedhashmap.EvictionListener;
import com.googlecode.concurrentlinkedhashmap.Weigher;
import org.apache.log4j.Logger;

public class ResponseCache {
    private static final Logger logger = Logger.getLogger(ResponseCache.class);

    private final ConcurrentLinkedHashMap<String, CachedResponse> cachedResponses;

    public ResponseCache(WebProxyOptions options) {
        //create LRU cache
        cachedResponses = new ConcurrentLinkedHashMap.Builder<String, CachedResponse>()
            .concurrencyLevel(options.cacheConcurrencyLevel())
            .maximumWeightedCapacity(options.maximumCacheSize())
            .weigher(new Weigher<CachedResponse>() {
                @Override
                public int weightOf(CachedResponse value) {
                    return value.getContentSize();
                }
            })
            .listener(new EvictionListener<String, CachedResponse>() {
                @Override
                public void onEviction(String key, CachedResponse value) {
                    logger.debug("Evicting from cache: " + key);
                }
            })
            .build();
    }

    public CachedResponse get(String uri) {
        return cachedResponses.get(uri);
    }

    public void put(String uri, CachedResponse res) {
        cachedResponses.put(uri, res);
    }

    public void remove(String uri, CachedResponse expected) {
        if (expected == null) {
            cachedResponses.remove(uri);
        } else {
            cachedResponses.remove(uri, expected);
        }
    }
}