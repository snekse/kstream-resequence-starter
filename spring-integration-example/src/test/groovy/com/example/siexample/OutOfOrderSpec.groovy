package com.example.siexample

import com.example.sampledomain.EntityType
import com.example.sampledomain.SampleRecord
import com.example.siexample.producer.SampleProducer
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

import java.time.Duration

@SpringBootTest(properties = [
    'spring.embedded.kafka.brokers.property=spring.kafka.bootstrap-servers',
    'app.pipeline.source.topic=si-test-topic',
    'resequence.group-timeout=2s'])
@EmbeddedKafka(topics = ['${app.pipeline.source.topic}', '${app.pipeline.source.topic}-resequenced'], partitions = 3)
@ActiveProfiles('test')
@DirtiesContext
class OutOfOrderSpec extends Specification {

    @Autowired
    SampleProducer producer

    @Autowired
    ConsumerFactory<Object, Object> consumerFactory

    @Autowired
    KafkaTemplate<Object, Object> kafkaTemplate

    @Value('${app.pipeline.source.topic}')
    String sourceTopic

    @Value('${app.pipeline.sink.topic}')
    String sinkTopic

    def 'should consume messages in logical order despite out-of-order production'() {
        given: 'a test consumer with unique group id'
        def uniqueId = UUID.randomUUID().toString().take(8)
        def consumer = consumerFactory.createConsumer("test-group-$uniqueId", "test-client-$uniqueId")

        def partitions = (0..2).collect { new TopicPartition(sinkTopic, it) }
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        consumer.poll(Duration.ofMillis(100))

        when: 'messages are produced and thereafter consumed'
        producer.produceSampleData()

        def records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(15), 6)
        consumer.close()

        then: 'all records are received'
        records.count() == 6

        and: 'records for client 1001 are in correct logical order (Create -> Update -> Delete)'
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

        def sourcePartitions = (0..2).collect { new TopicPartition(sourceTopic, it) }
        def sinkPartitions = (0..2).collect { new TopicPartition(sinkTopic, it) }
        sourceConsumer.assign(sourcePartitions)
        sinkConsumer.assign(sinkPartitions)
        sourceConsumer.seekToEnd(sourcePartitions)
        sinkConsumer.seekToEnd(sinkPartitions)
        sourceConsumer.poll(Duration.ofMillis(100))
        sinkConsumer.poll(Duration.ofMillis(100))

        when: 'messages are produced'
        producer.produceSampleData()

        and: 'consumed from both topics'
        def sourceRecords = KafkaTestUtils.getRecords(sourceConsumer, Duration.ofSeconds(15), 6)
        def sinkRecords = KafkaTestUtils.getRecords(sinkConsumer, Duration.ofSeconds(15), 6)
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

    def 'should order by full comparator logic: operation type, then payload timestamp, then Kafka metadata'() {
        given: 'a test consumer'
        def uniqueId = UUID.randomUUID().toString().take(8)
        def consumer = consumerFactory.createConsumer("complex-group-$uniqueId", "complex-client-$uniqueId")

        def partitions = (0..2).collect { new TopicPartition(sinkTopic, it) }
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        consumer.poll(Duration.ofMillis(100))

        and: 'complex records with varying timestamps'
        def clientId = '2001'
        def baseTime = System.currentTimeMillis()

        def create = buildRecord(2001L, 'CREATE', baseTime + 5000)
        def updateEarly = buildRecord(2001L, 'UPDATE', baseTime + 1000)
        def updateMiddle = buildRecord(2001L, 'UPDATE', baseTime + 2000)
        def updateLate = buildRecord(2001L, 'UPDATE', baseTime + 3000)
        def updateSameTime1 = buildRecord(2001L, 'UPDATE', baseTime + 4000)
        def updateSameTime2 = buildRecord(2001L, 'UPDATE', baseTime + 4000)
        def delete = buildRecord(2001L, 'DELETE', baseTime)

        when: 'records are produced in scrambled order'
        kafkaTemplate.send(sourceTopic, clientId, delete).get()
        kafkaTemplate.send(sourceTopic, clientId, updateLate).get()
        kafkaTemplate.send(sourceTopic, clientId, updateSameTime2).get()
        Thread.sleep(50)
        kafkaTemplate.send(sourceTopic, clientId, updateSameTime1).get()
        kafkaTemplate.send(sourceTopic, clientId, create).get()
        kafkaTemplate.send(sourceTopic, clientId, updateMiddle).get()
        kafkaTemplate.send(sourceTopic, clientId, updateEarly).get()

        and: 'records are consumed from resequenced topic'
        def records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(15), 7)
        consumer.close()

        then: 'all 7 records are received'
        records.count() == 7

        and: 'extract records in order'
        def orderedRecords = records.toList().collect { it.value() as SampleRecord }

        and: 'CREATE comes first despite having later payload timestamp'
        orderedRecords[0].operationType == 'CREATE'

        and: 'UPDATEs come next, ordered by payload timestamp'
        orderedRecords[1].operationType == 'UPDATE'
        orderedRecords[1].timestamp == baseTime + 1000

        orderedRecords[2].operationType == 'UPDATE'
        orderedRecords[2].timestamp == baseTime + 2000

        orderedRecords[3].operationType == 'UPDATE'
        orderedRecords[3].timestamp == baseTime + 3000

        and: 'UPDATEs with same payload timestamp are both present (ordered by Kafka metadata)'
        orderedRecords[4].operationType == 'UPDATE'
        orderedRecords[4].timestamp == baseTime + 4000

        orderedRecords[5].operationType == 'UPDATE'
        orderedRecords[5].timestamp == baseTime + 4000

        and: 'DELETE comes last despite having earliest payload timestamp'
        orderedRecords[6].operationType == 'DELETE'
    }

    def 'should skip records with null keys without crashing'() {
        given: 'a test consumer'
        def uniqueId = UUID.randomUUID().toString().take(8)
        def consumer = consumerFactory.createConsumer("null-key-group-$uniqueId", "null-key-client-$uniqueId")

        def partitions = (0..2).collect { new TopicPartition(sinkTopic, it) }
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        consumer.poll(Duration.ofMillis(100))

        and: 'records with a mix of null and non-null keys'
        def clientId = '3001'
        def baseTime = System.currentTimeMillis()

        def validRecord1 = buildRecord(3001L, 'CREATE', baseTime)
        def validRecord2 = buildRecord(3001L, 'UPDATE', baseTime + 1000)
        def validRecord3 = buildRecord(3001L, 'DELETE', baseTime + 2000)

        when: 'records are produced including some with null keys'
        kafkaTemplate.send(sourceTopic, clientId, validRecord1).get()
        kafkaTemplate.send(sourceTopic, null, validRecord2).get() // Null key - should be skipped
        kafkaTemplate.send(sourceTopic, clientId, validRecord3).get()
        kafkaTemplate.send(sourceTopic, null, buildRecord(9999L, 'CREATE', baseTime)).get() // Another null key

        and: 'records are consumed from resequenced topic'
        def records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(15), 2)
        consumer.close()

        then: 'only records with valid keys are processed'
        records.count() == 2

        and: 'records are in correct order'
        def orderedRecords = records.toList().collect { it.value() as SampleRecord }
        orderedRecords[0].operationType == 'CREATE'
        orderedRecords[1].operationType == 'DELETE'

        and: 'the null-keyed UPDATE was skipped'
        !orderedRecords.any { it.operationType == 'UPDATE' }
    }

    def 'should handle tombstone records (null values) without crashing'() {
        given: 'a test consumer'
        def uniqueId = UUID.randomUUID().toString().take(8)
        def consumer = consumerFactory.createConsumer("tombstone-group-$uniqueId", "tombstone-client-$uniqueId")

        def partitions = (0..2).collect { new TopicPartition(sinkTopic, it) }
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        consumer.poll(Duration.ofMillis(100))

        and: 'records with a mix of normal records and tombstones'
        def clientId = '4001'
        def baseTime = System.currentTimeMillis()

        def create = buildRecord(4001L, 'CREATE', baseTime)
        def update = buildRecord(4001L, 'UPDATE', baseTime + 1000)
        def delete = buildRecord(4001L, 'DELETE', baseTime + 2000)

        when: 'records are produced including tombstones (null values)'
        kafkaTemplate.send(sourceTopic, clientId, delete).get()
        kafkaTemplate.send(sourceTopic, clientId, null).get() // Tombstone
        kafkaTemplate.send(sourceTopic, clientId, update).get()
        kafkaTemplate.send(sourceTopic, clientId, create).get()
        kafkaTemplate.send(sourceTopic, clientId, null).get() // Another tombstone

        and: 'records are consumed from resequenced topic'
        def records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(15), 5)
        consumer.close()

        then: 'all records are received including tombstones'
        records.count() == 5

        and: 'extract all values in order'
        def allValues = records.toList().collect { it.value() as SampleRecord }

        and: 'non-null records are in correct logical order first'
        def nonNullRecords = allValues.findAll { it != null }
        nonNullRecords.size() == 3
        nonNullRecords[0].operationType == 'CREATE'
        nonNullRecords[1].operationType == 'UPDATE'
        nonNullRecords[2].operationType == 'DELETE'

        and: 'tombstones are sorted to the end'
        def nullRecords = allValues.findAll { it == null }
        nullRecords.size() == 2
        allValues[-1] == null
        allValues[-2] == null
    }

    private static SampleRecord buildRecord(Long clientId, String operationType, Long timestamp) {
        SampleRecord.builder()
                .clientId(clientId)
                .operationType(operationType)
                .timestamp(timestamp)
                .entityType(EntityType.Parent)
                .transactionId(UUID.randomUUID())
                .build()
    }
}
