package com.example.sampleapp


import com.example.sampleapp.domain.SampleRecord
import com.example.sampleapp.producer.SampleProducer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.PendingFeature
import spock.lang.Specification

import java.time.Duration

@SpringBootTest(properties = [
    'spring.embedded.kafka.brokers.property=spring.kafka.bootstrap-servers',
    'app.pipeline.source.topic=test-topic'])
@EmbeddedKafka(topics = ['${app.pipeline.source.topic}', '${app.pipeline.source.topic}-resequenced'], partitions = 3)
@ActiveProfiles('test')
@DirtiesContext
class OutOfOrderSpec extends Specification {

    @Autowired
    SampleProducer producer

    @Autowired
    ConsumerFactory<Object, Object> consumerFactory

    @Value('${app.pipeline.sink.topic}')
    String topic

    @PendingFeature
    def 'should consume messages in logical order despite out-of-order production'() {
        given: 'a test consumer'
        def consumer = consumerFactory.createConsumer('test-group', 'test-client')
        consumer.subscribe([topic])

        when: 'messages are produced and thereafter consumed'
        producer.produceSampleData()

        // Use KafkaTestUtils to wait for exactly 6 records
        def records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 6)
        consumer.close()

        then: 'all records are received'
        records.count() == 6

        and: 'records for client 1001 are in correct logical order (Create -> Update -> Delete)'
        // Extract values from ConsumerRecords
        def allRecords = records.toList().collect { it.value() as SampleRecord }
        def client1Records = allRecords.findAll { it.clientId == 1001L }

        client1Records.size() == 3
        client1Records[0].operationType == 'CREATE'
        client1Records[0].newKey != null
        client1Records[1].operationType == 'UPDATE'
        client1Records[1].newKey != null
        client1Records[2].operationType == 'DELETE'
        client1Records[2].newKey != null
    }
}
