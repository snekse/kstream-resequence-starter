package com.example.sampleapp

import com.example.sampleapp.domain.EntityType
import com.example.sampleapp.domain.SampleRecord
import com.example.sampleapp.producer.SampleProducer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.spockframework.runtime.model.FeatureMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import spock.lang.PendingFeature
import spock.lang.Specification

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
@org.springframework.test.context.ActiveProfiles("test")
@DirtiesContext
class OutOfOrderTest extends Specification {

    @Autowired
    SampleProducer producer

    @Autowired
    TestListener listener

    @PendingFeature
    def "should consume messages in logical order despite out-of-order production"() {
        given: "a set of records produced out-of-order"
        producer.produceSampleData()

        when: "messages are consumed"
        List<SampleRecord> consumedRecords = []
        // We expect 5 records based on SampleProducer logic:
        // 3 for Client 1001, 1 for Client 1002, 2 for Client 1003 = 6 total actually.
        // Let's check SampleProducer logic:
        // 1001: 3 records
        // 1002: 1 record
        // 1003: 2 records
        // Total = 6.
        6.times {
            def record = listener.records.poll(10, TimeUnit.SECONDS)
            if (record != null) {
                consumedRecords.add(record)
            }
        }

        then: "all records are received"
        consumedRecords.size() == 6

        and: "records for client 1001 are in correct logical order (Create -> Update -> Delete)"
        def client1Records = consumedRecords.findAll { it.clientId == 1001L }
        client1Records.size() == 3
        client1Records[0].operationType == "CREATE"
        client1Records[1].operationType == "UPDATE"
        client1Records[2].operationType == "DELETE"

        // The above assertion is expected to fail because we are shuffling them and not resequencing.
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @Bean
        TestListener testListener() {
            return new TestListener()
        }

        @Bean
        org.springframework.kafka.test.EmbeddedKafkaBroker embeddedKafkaBroker() {
            return new org.springframework.kafka.test.EmbeddedKafkaKraftBroker(1, 1, "sample-topic")
                    .brokerListProperty("spring.embedded.kafka.brokers")
        }
    }

    static class TestListener {
        BlockingQueue<SampleRecord> records = new LinkedBlockingQueue<>()

        @KafkaListener(topics = "sample-topic", groupId = "test-group")
        void consume(SampleRecord record) {
            records.add(record)
        }
    }
}
