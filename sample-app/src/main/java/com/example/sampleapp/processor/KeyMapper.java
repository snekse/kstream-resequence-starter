package com.example.sampleapp.processor;

@FunctionalInterface
public interface KeyMapper<K, KR> {
    KR map(K key);
}
