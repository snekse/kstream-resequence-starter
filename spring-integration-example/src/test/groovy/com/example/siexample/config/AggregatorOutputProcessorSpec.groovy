package com.example.siexample.config

import com.example.sampledomain.BufferedRecord
import com.example.sampledomain.EntityType
import com.example.sampledomain.ResequenceComparator
import com.example.sampledomain.SampleRecord
import com.example.sampledomain.TombstoneSortOrder
import com.example.siexample.processor.KeyMapper
import org.springframework.integration.aggregator.MessageGroupProcessor
import org.springframework.integration.store.SimpleMessageGroup
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import spock.lang.Specification

class AggregatorOutputProcessorSpec extends Specification {

    def comparator = new ResequenceComparator(TombstoneSortOrder.LAST)
    KeyMapper<String, String> keyMapper = { key -> key + "-sorted" }
    def valueEnricher = { String newKey, SampleRecord record -> record.setNewKey(newKey) }

    MessageGroupProcessor outputProcessor

    def setup() {
        outputProcessor = new IntegrationFlowConfig().resequenceOutputProcessor(comparator, keyMapper, valueEnricher)
    }

    def 'should sort messages correctly using comparator'() {
        given: 'messages with different operation types'
        def baseTime = System.currentTimeMillis()
        def deleteMsg = createMessage('DELETE', baseTime + 2000, '1001', 0, 2, baseTime + 2000)
        def updateMsg = createMessage('UPDATE', baseTime + 1000, '1001', 0, 1, baseTime + 1000)
        def createMsg = createMessage('CREATE', baseTime, '1001', 0, 0, baseTime)

        def group = new SimpleMessageGroup('test-group')
        group.add(deleteMsg)
        group.add(updateMsg)
        group.add(createMsg)

        when: 'output processor processes the group'
        def result = outputProcessor.processMessageGroup(group) as List<Message<?>>

        then: 'messages are sorted: CREATE, UPDATE, DELETE'
        result.size() == 3
        (result[0].payload as SampleRecord).operationType == 'CREATE'
        (result[1].payload as SampleRecord).operationType == 'UPDATE'
        (result[2].payload as SampleRecord).operationType == 'DELETE'
    }

    def 'should apply key mapping and value enrichment'() {
        given: 'a message with original key'
        def baseTime = System.currentTimeMillis()
        def msg = createMessage('CREATE', baseTime, '1001', 0, 0, baseTime)

        def group = new SimpleMessageGroup('test-group')
        group.add(msg)

        when: 'output processor processes the group'
        def result = outputProcessor.processMessageGroup(group) as List<Message<?>>

        then: 'mapped key header is set'
        result[0].headers.get('mappedKey') == '1001-sorted'

        and: 'value is enriched with new key'
        (result[0].payload as SampleRecord).newKey == '1001-sorted'
    }

    def 'should handle tombstones (null records within BufferedRecord)'() {
        given: 'messages including a tombstone'
        def baseTime = System.currentTimeMillis()
        def createMsg = createMessage('CREATE', baseTime, '1001', 0, 0, baseTime)
        def tombstoneMsg = createTombstoneMessage('1001', 0, 1, baseTime + 1000)

        def group = new SimpleMessageGroup('test-group')
        group.add(createMsg)
        group.add(tombstoneMsg)

        when: 'output processor processes the group'
        def result = outputProcessor.processMessageGroup(group) as List<Message<?>>

        then: 'both messages are returned'
        result.size() == 2

        and: 'non-tombstone comes first'
        result[0].headers.get('isTombstone') == false
        (result[0].payload as SampleRecord).operationType == 'CREATE'

        and: 'tombstone comes last with marker'
        result[1].headers.get('isTombstone') == true
        result[1].payload == 'TOMBSTONE'
    }

    def 'should handle empty message groups'() {
        given: 'an empty message group'
        def group = new SimpleMessageGroup('test-group')

        when: 'output processor processes the group'
        def result = outputProcessor.processMessageGroup(group) as List<Message<?>>

        then: 'result is an empty list'
        result.isEmpty()
    }

    private Message<?> createMessage(String operationType, long payloadTimestamp,
                                     String originalKey, int partition, long offset, long kafkaTimestamp) {
        def record = SampleRecord.builder()
                .clientId(Long.valueOf(originalKey))
                .operationType(operationType)
                .timestamp(payloadTimestamp)
                .entityType(EntityType.Parent)
                .build()

        def buffered = BufferedRecord.builder()
                .record(record)
                .partition(partition)
                .offset(offset)
                .timestamp(kafkaTimestamp)
                .build()

        return MessageBuilder.withPayload(buffered)
                .setHeader(KafkaHeaders.RECEIVED_KEY, originalKey)
                .build()
    }

    private Message<?> createTombstoneMessage(String originalKey, int partition, long offset, long kafkaTimestamp) {
        def buffered = BufferedRecord.builder()
                .record(null)
                .partition(partition)
                .offset(offset)
                .timestamp(kafkaTimestamp)
                .build()

        return MessageBuilder.withPayload(buffered)
                .setHeader(KafkaHeaders.RECEIVED_KEY, originalKey)
                .build()
    }
}
