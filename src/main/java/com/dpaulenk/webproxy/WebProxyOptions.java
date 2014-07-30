package com.dpaulenk.webproxy;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

//it's easy to pass this through in every object that needs it, but it's much simpler with singleton
public class WebProxyOptions {
    private static final Logger logger = Logger.getLogger(WebProxyOptions.class);

    private static final WebProxyOptions instance = new WebProxyOptions();

    public static WebProxyOptions getInstance() {
        return instance;
    }

    private WebProxyOptions() {}

    private int listenPort = 8181;

    private int serverThreadsCount = 1;
    private int inboundThreadsCount = 4;
    private int outboundThreadsCount = 4;
    private int maximumAwaitingAccept = 100;
    private int cacheConcurrencyLevel = 8;

    private int maxCumulationBufferComponents = 1024;
    private int maxChunkSize = 8192 * 2;
    private int maxCachedResponseSize = 65536*3;
    private int maximumCacheSize = 1000*maxCachedResponseSize;

    private boolean cachingEnabled = true;

    private String[] blackList = new String[0];

    public int listenPort() {
        return listenPort;
    }

    public int serverThreadsCount() {
        return serverThreadsCount;
    }

    public int inboundThreadsCount() {
        return inboundThreadsCount;
    }

    public int outboundThreadsCount() {
        return outboundThreadsCount;
    }

    public int maximumAwaitingAccept() {
        return maximumAwaitingAccept;
    }

    public boolean cachingEnabled() {
        return cachingEnabled;
    }

    public long maximumCacheSize() {
        return maximumCacheSize;
    }

    public int cacheConcurrencyLevel() {
        return cacheConcurrencyLevel;
    }

    public int getMaxCumulationBufferComponents() {
        return maxCumulationBufferComponents;
    }

    public int getMaxCachedResponseSize() {
        return maxCachedResponseSize;
    }

    public int maxChunkSize() {
        return maxChunkSize;
    }

    public String[] blackList() {
        return blackList;
    }

    public void loadFromFile(String optionsFileUri) {
        Properties props = new Properties();
        try {
            props.load(WebProxyOptions.class.getResourceAsStream(optionsFileUri));

            listenPort = intProp(props, "listenPort", listenPort);
            serverThreadsCount = intProp(props, "serverThreadsCount", serverThreadsCount);
            inboundThreadsCount = intProp(props, "inboundThreadsCount", inboundThreadsCount);
            outboundThreadsCount = intProp(props, "outboundThreadsCount", outboundThreadsCount);
            maximumAwaitingAccept = intProp(props, "maximumAwaitingAccept", maximumAwaitingAccept);
            cachingEnabled = booleanProp(props, "cachingEnabled", cachingEnabled);
            maximumCacheSize = intProp(props, "maximumCacheSize", maximumCacheSize);
            cacheConcurrencyLevel = intProp(props, "cacheConcurrencyLevel", cacheConcurrencyLevel);
            maxCumulationBufferComponents = intProp(props, "maxCumulationBufferComponents", maxCumulationBufferComponents);
            maxCachedResponseSize = intProp(props, "maxCachedResponseSize", maxCachedResponseSize);
            maxChunkSize = intProp(props, "maxChunkSize", maxChunkSize);
            blackList = strinArrayProp(props, "blackList", blackList);
        } catch (IOException e) {
            logger.error("Error loading configuration file: ", e);
        }
    }

    private String[] strinArrayProp(Properties props, String name, String[] defaultValue) {

        //todo: get rid of hardcoded value
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < 50; ++i) {
            String val = props.getProperty(name + "." + i);
            if (val != null) {
                values.add(val);
            }
        }

        if (values.isEmpty()) {
            return defaultValue;
        }

        return values.toArray(new String[values.size()]);
    }

    private int intProp(Properties props, String name, int defaultValue) {
        String sVal = props.getProperty(name);
        if (sVal == null) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(sVal);
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }
    private boolean booleanProp(Properties props, String name, boolean defaultValue) {
        String sVal = props.getProperty(name);
        if (sVal == null) {
            return defaultValue;
        }

        try {
            return Boolean.parseBoolean(sVal);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
