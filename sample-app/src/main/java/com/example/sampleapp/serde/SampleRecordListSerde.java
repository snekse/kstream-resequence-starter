package com.example.sampleapp.serde;

import com.example.sampleapp.domain.SampleRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

public class SampleRecordListSerde implements Serde<List<SampleRecord>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<SampleRecord>> TYPE_REF = new TypeReference<>() {};

    @Override
    public Serializer<List<SampleRecord>> serializer() {
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
    public Deserializer<List<SampleRecord>> deserializer() {
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
