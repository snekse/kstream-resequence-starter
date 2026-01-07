package com.example.sampleapp

import com.example.sampleapp.domain.EntityType
import com.example.sampleapp.domain.SampleRecord
import com.example.sampleapp.producer.SampleProducer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.spockframework.runtime.model.FeatureMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.PendingFeature
import spock.lang.Specification

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = [
    'spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}', 
    'app.topic.name=test-topic'])
@EmbeddedKafka(topics = ['${app.topic.name}'], partitions = 3)
@ActiveProfiles('test')
@DirtiesContext
class OutOfOrderSpec extends Specification {

    @Autowired
    SampleProducer producer

    @Autowired
    TestListener listener

    @PendingFeature
    def 'should consume messages in logical order despite out-of-order production'() {
        given: 'a set of records produced out-of-order'
        producer.produceSampleData()

        when: 'messages are consumed'
        List<SampleRecord> consumedRecords = []
        // We expect 6 records based on SampleProducer logic:
        // 3 for Client 1001, 1 for Client 1002, 2 for Client 1003
        6.times {
            def record = listener.records.poll(10, TimeUnit.SECONDS)
            if (record != null) {
                consumedRecords << record
            }
        }

        then: 'all records are received'
        consumedRecords.size() == 6

        and: 'records for client 1001 are in correct logical order (Create -> Update -> Delete)'
        def client1Records = consumedRecords.findAll { it.clientId == 1001L }
        client1Records.size() == 3
        client1Records[0].operationType == 'CREATE'
        client1Records[1].operationType == 'UPDATE'
        client1Records[2].operationType == 'DELETE'

        // The above assertion is expected to fail because we are shuffling them and not resequencing.
    }

    @TestConfiguration
    static class Config {
        @Bean
        TestListener testListener() {
            return new TestListener()
        }
    }

    static class TestListener {
        BlockingQueue<SampleRecord> records = new LinkedBlockingQueue<>()

        @KafkaListener(topics = '${app.topic.name}', groupId = 'test-group')
        void consume(SampleRecord record) {
            records.add(record)
        }
    }
}
