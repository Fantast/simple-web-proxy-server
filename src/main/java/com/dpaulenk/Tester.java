package com.dpaulenk;

import com.dpaulenk.webproxy.utils.ProxyUtils;

import java.util.Date;

public class Tester {
    public static void main(String[] args) {
        System.out.println(System.currentTimeMillis() - 5*24*3600000);

        Date d = ProxyUtils.parseDate("Fri, 29 Aug 2014 03:27:19 GMT");
        System.out.println(d);

    }
}
