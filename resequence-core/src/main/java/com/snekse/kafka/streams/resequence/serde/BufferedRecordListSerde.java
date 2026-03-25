package com.snekse.kafka.streams.resequence.serde;

import com.snekse.kafka.streams.resequence.domain.BufferedRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

public class BufferedRecordListSerde<V> implements Serde<List<BufferedRecord<V>>> {

    private final Serializer<V> valueSerializer;
    private final Deserializer<V> valueDeserializer;

    public BufferedRecordListSerde(Serde<V> valueSerde) {
        this.valueSerializer = valueSerde.serializer();
        this.valueDeserializer = valueSerde.deserializer();
    }

    @Override
    public Serializer<List<BufferedRecord<V>>> serializer() {
        return (topic, data) -> {
            if (data == null) return null;
            try {
                var baos = new ByteArrayOutputStream();
                var out = new DataOutputStream(baos);
                out.writeInt(data.size());
                for (BufferedRecord<V> record : data) {
                    out.writeInt(record.partition());
                    out.writeLong(record.offset());
                    out.writeLong(record.timestamp());
                    V value = record.record();
                    if (value == null) {
                        out.writeInt(-1);
                    } else {
                        byte[] valueBytes = valueSerializer.serialize(topic, value);
                        out.writeInt(valueBytes.length);
                        out.write(valueBytes);
                    }
                }
                out.flush();
                return baos.toByteArray();
            } catch (SerializationException e) {
                throw e;
            } catch (Exception e) {
                throw new SerializationException(
                        "Failed to serialize buffered record list for topic '" + topic + "'",
                        e
                );
            }
        };
    }

    @Override
    public Deserializer<List<BufferedRecord<V>>> deserializer() {
        return (topic, data) -> {
            if (data == null) return new ArrayList<>();
            try {
                var in = new DataInputStream(new ByteArrayInputStream(data));
                int count = in.readInt();
                var records = new ArrayList<BufferedRecord<V>>(count);
                for (int i = 0; i < count; i++) {
                    int partition = in.readInt();
                    long offset = in.readLong();
                    long timestamp = in.readLong();
                    int valueLength = in.readInt();
                    V value;
                    if (valueLength == -1) {
                        value = null;
                    } else {
                        byte[] valueBytes = new byte[valueLength];
                        in.readFully(valueBytes);
                        value = valueDeserializer.deserialize(topic, valueBytes);
                    }
                    records.add(new BufferedRecord<>(value, partition, offset, timestamp));
                }
                return records;
            } catch (SerializationException e) {
                throw e;
            } catch (Exception e) {
                throw new SerializationException(
                        "Failed to deserialize buffered record list for topic '" + topic + "'",
                        e
                );
            }
        };
    }
}
