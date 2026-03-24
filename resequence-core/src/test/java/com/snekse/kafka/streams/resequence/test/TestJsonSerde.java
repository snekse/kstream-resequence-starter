package com.snekse.kafka.streams.resequence.test;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only Jackson-based serde for use in unit tests.
 * Not part of the production API — Jackson is a test dependency only.
 */
public class TestJsonSerde<T> implements Serde<T> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final Class<T> type;

    public TestJsonSerde(Class<T> type) {
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new SerializationException("Test serde serialization failed", e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, data) -> {
            if (data == null) return null;
            try {
                return MAPPER.readValue(data, type);
            } catch (Exception e) {
                throw new SerializationException("Test serde deserialization failed", e);
            }
        };
    }
}
