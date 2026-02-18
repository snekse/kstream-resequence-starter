package com.example.siexample.processor;

@FunctionalInterface
public interface KeyMapper<K, KR> {
    KR map(K key);
}
