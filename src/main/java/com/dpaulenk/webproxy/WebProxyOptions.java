package com.dpaulenk.webproxy;

//it's easy to pass this through in every object that needs it, but it's much simpler with singleton
public class WebProxyOptions {
    private static final WebProxyOptions instance = new WebProxyOptions();

    public static WebProxyOptions getInstance() {
        return instance;
    }

    private WebProxyOptions() {
    }





}
