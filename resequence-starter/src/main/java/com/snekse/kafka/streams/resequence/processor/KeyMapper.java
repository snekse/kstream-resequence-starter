package com.snekse.kafka.streams.resequence.processor;

@FunctionalInterface
public interface KeyMapper<K, KR> {
    KR map(K key);
}
