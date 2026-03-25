package com.snekse.kafka.streams.resequence

import com.snekse.kafka.streams.resequence.domain.BufferedRecord
import com.snekse.kafka.streams.resequence.domain.ResequenceComparator
import com.snekse.kafka.streams.resequence.processor.KeyMapper
import com.snekse.kafka.streams.resequence.processor.ValueMapper
import com.snekse.kafka.streams.resequence.test.TestFixtures
import com.snekse.kafka.streams.resequence.test.TestFixtures.TestRecord
import com.snekse.kafka.streams.resequence.test.TestJsonSerde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.time.Duration

class ResequencerBuilderSpec extends Specification {

    static final ResequenceComparator<TestRecord> TEST_COMPARATOR = { BufferedRecord<TestRecord> a, BufferedRecord<TestRecord> b ->
        def r1 = a.record
        def r2 = b.record
        if (r1 == null && r2 == null) return 0
        if (r1 == null) return 1
        if (r2 == null) return -1
        return Long.compare(r1.ts, r2.ts)
    } as ResequenceComparator<TestRecord>

    @AutoCleanup
    TopologyTestDriver driver

    def 'should throw when comparator is missing'() {
        when:
        Resequencer.builder()
            .valueSerde(new TestJsonSerde<>(TestRecord))
            .keySerde(Serdes.String())
            .build()

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'comparator is required'
    }

    def 'should throw when valueSerde is missing'() {
        when:
        Resequencer.builder()
            .comparator(TEST_COMPARATOR)
            .keySerde(Serdes.String())
            .build()

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'valueSerde is required'
    }

    def 'should throw when keySerde is missing'() {
        when:
        Resequencer.builder()
            .comparator(TEST_COMPARATOR)
            .valueSerde(new TestJsonSerde<>(TestRecord))
            .build()

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'keySerde is required'
    }

    def 'should build with defaults and produce working processor'() {
        given: 'a resequencer built with required fields only'
        def valueSerde = new TestJsonSerde<>(TestRecord)
        def resequencer = Resequencer.<String, TestRecord>builder()
            .comparator(TEST_COMPARATOR)
            .valueSerde(valueSerde)
            .keySerde(Serdes.String())
            .build()

        and: 'a topology wired via addTo'
        def topology = new Topology()
        topology.addSource('source',
                Serdes.String().deserializer(),
                valueSerde.deserializer(),
                'input')
        resequencer.addTo(topology, 'resequencer', 'source')
        topology.addSink('sink', 'output',
                Serdes.String().serializer(),
                valueSerde.serializer(),
                'resequencer')

        driver = new TopologyTestDriver(topology, TestFixtures.driverConfig())

        def inputTopic = driver.createInputTopic('input',
                Serdes.String().serializer(), valueSerde.serializer())
        def outputTopic = driver.createOutputTopic('output',
                Serdes.String().deserializer(), valueSerde.deserializer())

        when: 'out-of-order records are sent'
        inputTopic.pipeInput('k1', new TestRecord('B', 2000L))
        inputTopic.pipeInput('k1', new TestRecord('A', 1000L))

        and: 'flush triggers (default 2s)'
        driver.advanceWallClockTime(Duration.ofSeconds(2))

        then: 'records are sorted'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2
        results[0].value.ts == 1000L
        results[1].value.ts == 2000L
    }

    def 'should use identity key mapper when none provided'() {
        given: 'a resequencer without keyMapper'
        def valueSerde = new TestJsonSerde<>(TestRecord)
        def resequencer = Resequencer.<String, TestRecord>builder()
            .comparator(TEST_COMPARATOR)
            .valueSerde(valueSerde)
            .keySerde(Serdes.String())
            .build()

        and: 'a topology'
        def topology = new Topology()
        topology.addSource('source',
                Serdes.String().deserializer(),
                valueSerde.deserializer(),
                'input')
        resequencer.addTo(topology, 'resequencer', 'source')
        topology.addSink('sink', 'output',
                Serdes.String().serializer(),
                valueSerde.serializer(),
                'resequencer')

        driver = new TopologyTestDriver(topology, TestFixtures.driverConfig())

        def inputTopic = driver.createInputTopic('input',
                Serdes.String().serializer(), valueSerde.serializer())
        def outputTopic = driver.createOutputTopic('output',
                Serdes.String().deserializer(), valueSerde.deserializer())

        when:
        inputTopic.pipeInput('myKey', new TestRecord('A', 1000L))
        driver.advanceWallClockTime(Duration.ofSeconds(2))

        then: 'output key is unchanged (identity mapping)'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 1
        results[0].key == 'myKey'
    }

    def 'should use custom state store name'() {
        given:
        def valueSerde = new TestJsonSerde<>(TestRecord)
        def resequencer = Resequencer.<String, TestRecord>builder()
            .comparator(TEST_COMPARATOR)
            .valueSerde(valueSerde)
            .keySerde(Serdes.String())
            .stateStoreName('custom-store')
            .build()

        expect:
        resequencer.stateStoreName() == 'custom-store'
    }

    def 'should expose default state store name'() {
        given:
        def valueSerde = new TestJsonSerde<>(TestRecord)
        def resequencer = Resequencer.<String, TestRecord>builder()
            .comparator(TEST_COMPARATOR)
            .valueSerde(valueSerde)
            .keySerde(Serdes.String())
            .build()

        expect:
        resequencer.stateStoreName() == 'resequence-buffer'
    }

    def 'should re-key using builder keyMapper'() {
        given: 'a resequencer with keyMapper via builder'
        def valueSerde = new TestJsonSerde<>(TestRecord)
        def resequencer = Resequencer.<Long, TestRecord>builder()
            .comparator(TEST_COMPARATOR)
            .valueSerde(valueSerde)
            .keySerde(Serdes.Long())
            .keyMapper({ Long key -> key + '-mapped' } as KeyMapper<Long, String>)
            .build()

        and: 'a topology'
        def topology = new Topology()
        topology.addSource('source',
                Serdes.Long().deserializer(),
                valueSerde.deserializer(),
                'input')
        resequencer.addTo(topology, 'resequencer', 'source')
        topology.addSink('sink', 'output',
                Serdes.String().serializer(),
                valueSerde.serializer(),
                'resequencer')

        driver = new TopologyTestDriver(topology, TestFixtures.driverConfig())

        def inputTopic = driver.createInputTopic('input',
                Serdes.Long().serializer(), valueSerde.serializer())
        def outputTopic = driver.createOutputTopic('output',
                Serdes.String().deserializer(), valueSerde.deserializer())

        when:
        inputTopic.pipeInput(42L, new TestRecord('B', 2000L))
        inputTopic.pipeInput(42L, new TestRecord('A', 1000L))
        driver.advanceWallClockTime(Duration.ofSeconds(2))

        then: 'output keys are mapped and records sorted'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2
        results[0].key == '42-mapped'
        results[0].value.ts == 1000L
        results[1].key == '42-mapped'
        results[1].value.ts == 2000L
    }

    def 'should apply value mapper via builder'() {
        given: 'a resequencer with valueMapper via builder'
        def valueSerde = new TestJsonSerde<>(TestRecord)
        def resequencer = Resequencer.<String, TestRecord>builder()
            .comparator(TEST_COMPARATOR)
            .valueSerde(valueSerde)
            .keySerde(Serdes.String())
            .keyMapper({ String key -> key + '-enriched' } as KeyMapper<String, String>)
            .valueMapper({ String outputKey, BufferedRecord<TestRecord> buffered ->
                def record = buffered.record
                if (record != null) {
                    record.type = record.type + ':' + outputKey
                }
                record
            } as ValueMapper<String, TestRecord, TestRecord>)
            .build()

        and: 'a topology'
        def topology = new Topology()
        topology.addSource('source',
                Serdes.String().deserializer(),
                valueSerde.deserializer(),
                'input')
        resequencer.addTo(topology, 'resequencer', 'source')
        topology.addSink('sink', 'output',
                Serdes.String().serializer(),
                valueSerde.serializer(),
                'resequencer')

        driver = new TopologyTestDriver(topology, TestFixtures.driverConfig())

        def inputTopic = driver.createInputTopic('input',
                Serdes.String().serializer(), valueSerde.serializer())
        def outputTopic = driver.createOutputTopic('output',
                Serdes.String().deserializer(), valueSerde.deserializer())

        when:
        inputTopic.pipeInput('k1', new TestRecord('A', 1000L))
        driver.advanceWallClockTime(Duration.ofSeconds(2))

        then: 'value is enriched with mapped key'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 1
        results[0].value.type == 'A:k1-enriched'
    }
}
