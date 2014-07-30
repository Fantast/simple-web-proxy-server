package com.dpaulenk.webproxy.utils;

public interface Processor<T> {
    boolean process(T value);
}
