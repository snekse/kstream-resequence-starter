package com.example.sampleapp.serde;

import com.example.sampledomain.BufferedRecord;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

public class BufferedRecordListSerde<T> implements Serde<List<BufferedRecord<T>>> {

    private final ObjectMapper mapper;
    private final JavaType javaType;

    public BufferedRecordListSerde(Class<T> recordType) {
        this.mapper = new ObjectMapper();
        this.javaType = mapper.getTypeFactory().constructCollectionType(
                List.class,
                mapper.getTypeFactory().constructParametricType(BufferedRecord.class, recordType)
        );
    }

    @Override
    public Serializer<List<BufferedRecord<T>>> serializer() {
        return (topic, data) -> {
            if (data == null) return null;
            try {
                return mapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize list", e);
            }
        };
    }

    @Override
    public Deserializer<List<BufferedRecord<T>>> deserializer() {
        return (topic, data) -> {
            if (data == null) return new ArrayList<>();
            try {
                return mapper.readValue(data, javaType);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize list", e);
            }
        };
    }
}
