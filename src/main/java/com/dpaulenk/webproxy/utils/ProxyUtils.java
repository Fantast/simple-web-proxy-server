package com.dpaulenk.webproxy.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;

import javax.xml.ws.spi.http.HttpHandler;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;

public class ProxyUtils {
    public static final String PROXY_CONNECTION = "Proxy-connection";

    public static final String[] HOP_HEADERS = new String[] {
        "Connection",
        "Keep-Alive",
        "Proxy-Authenticate",
        "Proxy-Authorization",
        "TE",
        "Trailers",
        "Transfer-Encoding",
        "Upgrade"
    };

    public static final HttpResponseStatus CONNECTION_ESTABLISHED =
        new HttpResponseStatus(200, "HTTP/1.1 200 Connection established");

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private static final String DATE_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz";

    public static HttpRequest copyRequest(HttpRequest original) {
        HttpRequest result;
        if (original instanceof FullHttpRequest) {
            ByteBuf content = ((FullHttpRequest) original).content();
            result = new DefaultFullHttpRequest(original.getProtocolVersion(), original.getMethod(), original.getUri(), content);
            return result;
        } else {
            result = new DefaultHttpRequest(original.getProtocolVersion(), original.getMethod(), original.getUri());
        }

        result.headers().add(original.headers());

        return result;
    }

    public static void prepareProxyRequest(HttpRequest req) {
        req.setUri(ProxyUtils.getUriWithoutHostAndPort(req));

        removeHopHeaders(req);

        modifyConnectionHeader(req);

        addViaHeader(req);
    }

    public static void prepareProxyResponse(HttpResponse res) {
        boolean keepAlive = HttpHeaders.isKeepAlive(res);

        removeHopHeaders(res);
        addViaHeader(res);

        res.headers().set(CONNECTION, keepAlive ? "keep-alive" : "close");
    }


    /**
     * http://tools.ietf.org/html/rfc2616#section-14.45
     */
    public static void addViaHeader(HttpMessage msg) {
        String viaValue = "1.1 " + ProxyUtils.getLocalHostName();
        msg.headers().set(HttpHeaders.Names.VIA, viaValue);
    }

    /**
     * Clients use Proxy-Connection header instead of Connection, when using proxy
     */
    private static void modifyConnectionHeader(HttpRequest req) {
        //change Proxy-Connection header to Connection

        HttpHeaders headers = req.headers();
        if (headers.contains(PROXY_CONNECTION)) {
            headers.set("Connection", headers.get(PROXY_CONNECTION));
            headers.remove(PROXY_CONNECTION);
        }
    }

    /**
     * see: http://tools.ietf.org/html/rfc2616#section-13.5.1
     */
    private static void removeHopHeaders(HttpMessage msg) {
        HttpHeaders headers = msg.headers();
        for (String hopHeader : HOP_HEADERS) {
            headers.remove(hopHeader);
        }
    }

    public static String getHostAndPort(HttpRequest req) {
        String hostAndPort = ProxyUtils.getHostAndPort(req.getUri());
        if (hostAndPort == null) {
            hostAndPort = HttpHeaders.getHost(req);
        }

        return hostAndPort;
    }

    public static String getHostAndPort(String uri) {
        int colonInd = uri.indexOf("://");
        if (colonInd != -1) {
            uri = uri.substring(colonInd + 3);
        }

        int slashInd = uri.indexOf("/");
        if (slashInd != -1) {
            uri = uri.substring(0, slashInd);
        }

        return uri.trim();
    }

    public static String getUriWithoutHostAndPort(HttpRequest req) {
        String uri = req.getUri();

        int colonInd = uri.indexOf("://");
        if (colonInd == -1) {
            return uri;
        }

        uri = uri.substring(colonInd + 3);

        int slashInd = uri.indexOf("/");
        if (slashInd != -1) {
            uri = uri.substring(slashInd);
        }

        return uri;
    }

    public static String getLocalHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ignore) {
        }
        return "localhost";
    }

    public static boolean isConnectRequest(HttpRequest req) {
        return HttpMethod.CONNECT.equals(req.getMethod());
    }

    public static boolean hasCacheControlValues(HttpMessage msg, String... values) {
        return !processCacheControlHeaders(msg, new HasNoValuesProcessor(values));
    }

    public static boolean processCacheControlHeaders(HttpMessage msg, Processor<String> processor) {
        return processCacheControlHeaders(msg.headers(), processor);
    }

    public static boolean processCacheControlHeaders(HttpHeaders headers, Processor<String> processor) {
        List<String> reqCacheControls = headers.getAll(CACHE_CONTROL);
        for (String reqCacheControl : reqCacheControls) {
            String values[] = reqCacheControl.split(",");
            for (String val : values) {
                if (!processor.process(val.trim())) {
                    return false;
                }
            }
        }
        return true;
    }

    public static Date parseDate(String dateString) {
        if (dateString == null) {
            return null;
        }

        SimpleDateFormat format = new SimpleDateFormat(DATE_PATTERN, Locale.US);
        format.setTimeZone(GMT);
        try {
            return format.parse(dateString);
        } catch (ParseException ignore) {
        }
        return null;
    }

    public static long determineMaxAge(HttpMessage msg) {
        return determineMaxAge(System.currentTimeMillis(), msg);
    }

    public static long determineMaxAge(long currentTime, HttpMessage msg) {
        final Holder<Long> maxAge = new Holder<Long>();
        processCacheControlHeaders(msg, new Processor<String>() {
            @Override
            public boolean process(String value) {
                if (value.startsWith("max-age=")) {
                    long age;
                    try {
                        age = Long.parseLong(value.substring("max-age=".length()));
                    } catch (NumberFormatException nfe) {
                        return true;
                    }
                    maxAge.set(age);
                    return false;
                }
                return true;
            }
        });

        if (maxAge.get() != null) {
            return maxAge.get();
        }

        Date d = parseDate(msg.headers().get(EXPIRES));
        if (d != null) {
            return Math.max(0, d.getTime() - currentTime);
        }

        return -1;
    }

    public static long determineLastModified(HttpMessage msg) {
        Date lastModified = parseDate(msg.headers().get(LAST_MODIFIED));
        if (lastModified != null) {
            return lastModified.getTime();
        }

        return -1;
    }

    public static DefaultFullHttpResponse simpleResponse(HttpResponseStatus status, String body) {
        if (body == null) {
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        } else {
            byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
            ByteBuf buf = Unpooled.copiedBuffer(bytes);

            DefaultFullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
            res.headers().set(CONTENT_LENGTH, bytes.length);
            res.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8");

            return res;
        }
    }
}
