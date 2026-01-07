package com.example.sampleapp.consumer;

import com.example.sampleapp.domain.SampleRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SampleConsumer {

    @KafkaListener(topics = "${app.topic.name:sample-topic}", groupId = "sample-group")
    public void consume(SampleRecord record) {
        log.info("Consumed record: {}", record);
    }
}
