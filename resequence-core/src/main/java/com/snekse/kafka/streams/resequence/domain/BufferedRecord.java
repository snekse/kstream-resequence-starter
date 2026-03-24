package com.snekse.kafka.streams.resequence.domain;

public record BufferedRecord<T>(T record, int partition, long offset, long timestamp) {}
