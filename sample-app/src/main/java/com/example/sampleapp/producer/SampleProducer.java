package com.example.sampleapp.producer;

import com.example.sampleapp.domain.EntityType;
import com.example.sampleapp.domain.SampleRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SampleProducer {

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    @Value("${app.topic.name:sample-topic}")
    private String topic;

    public void produceSampleData() {
        List<SampleRecord> records = generateRecords();

        // Shuffle deterministically
        Collections.shuffle(records, new Random(42));

        log.info("Producing {} records out of order...", records.size());
        records.forEach(record -> {
            log.info("Sending record: {}", record);
            kafkaTemplate.send(topic, record.getClientId(), record);
        });
    }

    private List<SampleRecord> generateRecords() {
        List<SampleRecord> records = new ArrayList<>();
        long timestamp = System.currentTimeMillis();
        long clientId = 1001L;

        // Sequence 1: Create -> Update -> Delete for Client 1001
        records.add(createRecord(clientId, "CREATE", timestamp, EntityType.Parent));
        records.add(createRecord(clientId, "UPDATE", timestamp + 1000, EntityType.Parent));
        records.add(createRecord(clientId, "DELETE", timestamp + 2000, EntityType.Parent));

        // Sequence 2: Child A for Client 1002
        clientId = 1002L;
        records.add(createRecord(clientId, "CREATE", timestamp, EntityType.ChildA));

        // Sequence 3: Child B for Client 1003
        clientId = 1003L;
        records.add(createRecord(clientId, "CREATE", timestamp, EntityType.ChildB));
        records.add(createRecord(clientId, "UPDATE", timestamp + 500, EntityType.ChildB));

        return records;
    }

    private SampleRecord createRecord(Long clientId, String opType, Long timestamp, EntityType entityType) {
        return SampleRecord.builder()
                .clientId(clientId)
                .operationType(opType)
                .transactionId(UUID.randomUUID())
                .timestamp(timestamp)
                .entityType(entityType)
                .build();
    }
}
