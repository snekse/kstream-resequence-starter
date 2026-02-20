package com.example.sampleapp.processor;

import com.example.sampleapp.domain.BufferedRecord;

@FunctionalInterface
public interface ValueMapper<V, VR> {
    VR mapValue(BufferedRecord<V> bufferedRecord);
}
