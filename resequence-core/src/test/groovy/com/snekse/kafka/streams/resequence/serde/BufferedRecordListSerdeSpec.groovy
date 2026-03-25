package com.snekse.kafka.streams.resequence.serde

import com.snekse.kafka.streams.resequence.domain.BufferedRecord
import com.snekse.kafka.streams.resequence.test.TestJsonSerde
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.Serdes
import spock.lang.Specification

class BufferedRecordListSerdeSpec extends Specification {

    def valueSerde = new TestJsonSerde<>(Map)
    def serde = new BufferedRecordListSerde<>(valueSerde)

    def 'should round-trip serialize and deserialize a list of buffered records'() {
        given: 'a list of buffered records wrapping simple maps'
        def records = [
            new BufferedRecord<Map>([name: 'Alice', age: 30], 0, 10L, 1000L),
            new BufferedRecord<Map>([name: 'Bob', age: 25], 1, 20L, 2000L)
        ]

        when: 'serialized then deserialized'
        def bytes = serde.serializer().serialize('test-topic', records)
        def result = serde.deserializer().deserialize('test-topic', bytes)

        then: 'the deserialized list matches the original'
        result.size() == 2
        result[0].record == [name: 'Alice', age: 30]
        result[0].partition == 0
        result[0].offset == 10L
        result[0].timestamp == 1000L
        result[1].record == [name: 'Bob', age: 25]
        result[1].partition == 1
        result[1].offset == 20L
        result[1].timestamp == 2000L
    }

    def 'should serialize null to null'() {
        expect:
        serde.serializer().serialize('test-topic', null) == null
    }

    def 'should deserialize null to empty list'() {
        expect:
        serde.deserializer().deserialize('test-topic', null) == []
    }

    def 'should handle tombstone values (null record) in round-trip'() {
        given: 'a list containing a tombstone and a normal record'
        def records = [
            new BufferedRecord<Map>(null, 0, 10L, 1000L),
            new BufferedRecord<Map>([name: 'Alice'], 1, 20L, 2000L)
        ]

        when: 'serialized then deserialized'
        def bytes = serde.serializer().serialize('test-topic', records)
        def result = serde.deserializer().deserialize('test-topic', bytes)

        then: 'tombstone is preserved as null'
        result.size() == 2
        result[0].record == null
        result[0].partition == 0
        result[0].offset == 10L
        result[1].record == [name: 'Alice']
    }

    def 'should round-trip an empty list'() {
        given:
        def records = []

        when:
        def bytes = serde.serializer().serialize('test-topic', records)
        def result = serde.deserializer().deserialize('test-topic', bytes)

        then:
        result == []
    }

    def 'should round-trip with string serde'() {
        given: 'a serde using simple string values'
        def stringSerde = new BufferedRecordListSerde<>(Serdes.String())
        def records = [
            new BufferedRecord<String>('hello', 0, 10L, 1000L),
            new BufferedRecord<String>('world', 1, 20L, 2000L)
        ]

        when:
        def bytes = stringSerde.serializer().serialize('test-topic', records)
        def result = stringSerde.deserializer().deserialize('test-topic', bytes)

        then:
        result.size() == 2
        result[0].record == 'hello'
        result[1].record == 'world'
    }

    def 'should throw SerializationException with topic details when serialization fails'() {
        given: 'a serde with a value serializer that throws'
        def failingSerde = new BufferedRecordListSerde<>(new org.apache.kafka.common.serialization.Serde<Object>() {
            org.apache.kafka.common.serialization.Serializer<Object> serializer() {
                return { String topic, Object data -> throw new RuntimeException('boom') } as org.apache.kafka.common.serialization.Serializer<Object>
            }
            org.apache.kafka.common.serialization.Deserializer<Object> deserializer() { return null }
        })
        def records = [new BufferedRecord<Object>(new Object(), 0, 10L, 1000L)]

        when:
        failingSerde.serializer().serialize('failing-topic', records)

        then:
        def ex = thrown(SerializationException)
        ex.message == "Failed to serialize buffered record list for topic 'failing-topic'"
    }

    def 'should throw SerializationException with topic details when deserialization fails'() {
        given: 'bytes claiming 1 record but truncated (no metadata follows)'
        def truncated = new byte[] { 0, 0, 0, 1 }

        when:
        serde.deserializer().deserialize('broken-topic', truncated)

        then:
        def ex = thrown(SerializationException)
        ex.message == "Failed to deserialize buffered record list for topic 'broken-topic'"
    }
}
