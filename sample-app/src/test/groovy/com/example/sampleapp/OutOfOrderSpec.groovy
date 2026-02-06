package com.example.sampleapp


import com.example.sampleapp.domain.SampleRecord
import com.example.sampleapp.producer.SampleProducer
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
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

    @Value('${app.pipeline.source.topic}')
    String sourceTopic

    @Value('${app.pipeline.sink.topic}')
    String sinkTopic

    def 'should consume messages in logical order despite out-of-order production'() {
        given: 'a test consumer with unique group id'
        def uniqueId = UUID.randomUUID().toString().take(8)
        def consumer = consumerFactory.createConsumer("test-group-$uniqueId", "test-client-$uniqueId")

        // Assign partitions and seek to end to ignore records from previous tests
        def partitions = (0..2).collect { new TopicPartition(sinkTopic, it) }
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        consumer.poll(Duration.ofMillis(100))

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

    def 'should have different operation order between source and resequenced topics'() {
        given: 'consumers for both source and sink topics with unique group ids'
        def uniqueId = UUID.randomUUID().toString().take(8)
        def sourceConsumer = consumerFactory.createConsumer("source-group-$uniqueId", "source-client-$uniqueId")
        def sinkConsumer = consumerFactory.createConsumer("sink-group-$uniqueId", "sink-client-$uniqueId")

        // Assign partitions and seek to end to ignore records from previous tests
        def sourcePartitions = (0..2).collect { new TopicPartition(sourceTopic, it) }
        def sinkPartitions = (0..2).collect { new TopicPartition(sinkTopic, it) }
        sourceConsumer.assign(sourcePartitions)
        sinkConsumer.assign(sinkPartitions)
        sourceConsumer.seekToEnd(sourcePartitions)
        sinkConsumer.seekToEnd(sinkPartitions)
        // Poll once to commit the seek
        sourceConsumer.poll(Duration.ofMillis(100))
        sinkConsumer.poll(Duration.ofMillis(100))

        when: 'messages are produced'
        producer.produceSampleData()

        and: 'consumed from both topics'
        def sourceRecords = KafkaTestUtils.getRecords(sourceConsumer, Duration.ofSeconds(10), 6)
        def sinkRecords = KafkaTestUtils.getRecords(sinkConsumer, Duration.ofSeconds(10), 6)
        sourceConsumer.close()
        sinkConsumer.close()

        then: 'both topics have 6 records'
        sourceRecords.count() == 6
        sinkRecords.count() == 6

        and: 'extract operation types for client 1001 from both topics'
        def sourceOps = sourceRecords.toList()
                .collect { it.value() as SampleRecord }
                .findAll { it.clientId == 1001L }
                .collect { it.operationType }

        def sinkOps = sinkRecords.toList()
                .collect { it.value() as SampleRecord }
                .findAll { it.clientId == 1001L }
                .collect { it.operationType }

        and: 'the operation order is different between source and sink'
        sourceOps != sinkOps

        and: 'sink has correct logical order'
        sinkOps == ['CREATE', 'UPDATE', 'DELETE']
    }
}
