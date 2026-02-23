package com.snekse.kafka.streams.resequence.serde

import com.snekse.kafka.streams.resequence.domain.BufferedRecord
import tools.jackson.databind.json.JsonMapper
import spock.lang.Specification

class BufferedRecordListSerdeSpec extends Specification {

    def mapper = JsonMapper.builder().build()
    def serde = new BufferedRecordListSerde<>(Map, mapper)

    def 'should round-trip serialize and deserialize a list of buffered records'() {
        given: 'a list of buffered records wrapping simple maps'
        def records = [
            BufferedRecord.<Map>builder()
                .record([name: 'Alice', age: 30])
                .partition(0)
                .offset(10L)
                .timestamp(1000L)
                .build(),
            BufferedRecord.<Map>builder()
                .record([name: 'Bob', age: 25])
                .partition(1)
                .offset(20L)
                .timestamp(2000L)
                .build()
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
}
