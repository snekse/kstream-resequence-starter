package com.snekse.kafka.streams.resequence.domain;

import java.util.Comparator;

@FunctionalInterface
public interface ResequenceComparator<V> extends Comparator<BufferedRecord<V>> {
}
