package com.dpaulenk.webproxy.utils;

public interface Getter<K, V> {
    V get(K key);
}
