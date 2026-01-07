package com.example.sampleapp.consumer;

import com.example.sampleapp.domain.SampleRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SampleConsumer {

    @KafkaListener(topics = "${app.topic.name:sample-topic}", groupId = "sample-group")
    public void consume(SampleRecord record,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("Consumed record for {}: {} from partition: {}, offset: {}",
                record.getClientId(), record, partition, offset);
    }
}
