package com.example.sampleapp.serde;

import com.example.sampleapp.domain.BufferedRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

public class BufferedRecordListSerde implements Serde<List<BufferedRecord>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<BufferedRecord>> TYPE_REF = new TypeReference<>() {};

    @Override
    public Serializer<List<BufferedRecord>> serializer() {
        return (topic, data) -> {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize list", e);
            }
        };
    }

    @Override
    public Deserializer<List<BufferedRecord>> deserializer() {
        return (topic, data) -> {
            if (data == null) return new ArrayList<>();
            try {
                return MAPPER.readValue(data, TYPE_REF);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize list", e);
            }
        };
    }
}
