package com.snekse.kafka.streams.resequence.listener

import com.snekse.kafka.streams.resequence.domain.BufferedRecord
import com.snekse.kafka.streams.resequence.domain.ResequenceComparator
import com.snekse.kafka.streams.resequence.domain.TombstoneSortOrder
import com.snekse.kafka.streams.resequence.processor.KeyMapper
import com.snekse.kafka.streams.resequence.processor.ResequenceProcessor
import com.snekse.kafka.streams.resequence.processor.ValueMapper
import com.snekse.kafka.streams.resequence.serde.BufferedRecordListSerde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.state.Stores
import org.springframework.kafka.support.serializer.JacksonJsonSerde
import spock.lang.AutoCleanup
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

import java.time.Duration

class ResequenceEventListenerSpec extends Specification {

    static final String SOURCE_TOPIC = 'input'
    static final String SINK_TOPIC = 'output'
    static final String STATE_STORE = 'resequence-store'
    static final Duration FLUSH_INTERVAL = Duration.ofMillis(100)

    static class TestRecord {
        String type
        long ts

        TestRecord() {}
        TestRecord(String type, long ts) { this.type = type; this.ts = ts }
    }

    static final ResequenceComparator<TestRecord> TEST_COMPARATOR = { BufferedRecord<TestRecord> a, BufferedRecord<TestRecord> b ->
        def r1 = a.record
        def r2 = b.record
        if (r1 == null && r2 == null) return 0
        if (r1 == null) return TombstoneSortOrder.LAST.signum
        if (r2 == null) return -TombstoneSortOrder.LAST.signum
        Long.compare(r1.ts, r2.ts)
    } as ResequenceComparator<TestRecord>

    @AutoCleanup
    TopologyTestDriver driver

    private static Properties driverConfig() {
        def props = new Properties()
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, 'test-app')
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.name)
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.name)
        props
    }

    private static Topology buildTopology(ResequenceEventListener listener) {
        def valueSerde = new JacksonJsonSerde<>(TestRecord)
        def bufferedSerde = new BufferedRecordListSerde<>(TestRecord, JsonMapper.builder().build())

        def topology = new Topology()
        topology.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE),
                Serdes.String(),
                bufferedSerde))

        topology.addSource('source',
                Serdes.String().deserializer(),
                valueSerde.deserializer(),
                SOURCE_TOPIC)

        topology.addProcessor('resequencer',
                () -> new ResequenceProcessor<>(TEST_COMPARATOR, STATE_STORE, FLUSH_INTERVAL, null, null, listener),
                'source')

        topology.connectProcessorAndStateStores('resequencer', STATE_STORE)

        topology.addSink('sink',
                SINK_TOPIC,
                Serdes.String().serializer(),
                valueSerde.serializer(),
                'resequencer')

        topology
    }

    def 'onRecordIgnored fires once for a null-key record and nothing is buffered or output'() {
        given:
        ResequenceEventListener listener = Mock()
        driver = new TopologyTestDriver(buildTopology(listener), driverConfig())
        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        when:
        inputTopic.pipeInput(null as String, new TestRecord('A', 1000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        1 * listener.onRecordIgnored()
        0 * listener.onRecordBuffered(_, _)
        0 * listener.onTombstoneReceived(_, _)
        outputTopic.readKeyValuesToList().isEmpty()
    }

    def 'onRecordBuffered fires for a normal (non-null-value) record'() {
        given:
        ResequenceEventListener listener = Mock()
        driver = new TopologyTestDriver(buildTopology(listener), driverConfig())
        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when:
        inputTopic.pipeInput('key1', new TestRecord('A', 1000L))

        then:
        1 * listener.onRecordBuffered('key1', { BufferedRecord br -> br.record != null })
        0 * listener.onTombstoneReceived(_, _)
        0 * listener.onRecordIgnored()
    }

    def 'both onTombstoneReceived and onRecordBuffered fire for a tombstone record'() {
        given:
        ResequenceEventListener listener = Mock()
        driver = new TopologyTestDriver(buildTopology(listener), driverConfig())
        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when:
        inputTopic.pipeInput('key1', null as TestRecord)

        then: 'tombstone fires first, then general buffered callback'
        1 * listener.onTombstoneReceived('key1', { BufferedRecord br -> br.record == null })
        1 * listener.onRecordBuffered('key1', { BufferedRecord br -> br.record == null })
        0 * listener.onRecordIgnored()
    }

    def 'onFlushStarted fires with isFullScan=true on first flush, false on subsequent flushes'() {
        given:
        ResequenceEventListener listener = Mock()
        driver = new TopologyTestDriver(buildTopology(listener), driverConfig())
        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        inputTopic.pipeInput('key1', new TestRecord('A', 1000L))

        when: 'first flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        1 * listener.onFlushStarted(true)

        when: 'second flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        1 * listener.onFlushStarted(false)
    }

    def 'onKeyFlushed fires once per distinct key with correct record count'() {
        given:
        ResequenceEventListener listener = Mock()
        driver = new TopologyTestDriver(buildTopology(listener), driverConfig())
        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when:
        inputTopic.pipeInput('key1', new TestRecord('A', 1000L))
        inputTopic.pipeInput('key1', new TestRecord('B', 2000L))
        inputTopic.pipeInput('key2', new TestRecord('C', 3000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        1 * listener.onKeyFlushed('key1', 2)
        1 * listener.onKeyFlushed('key2', 1)
    }

    def 'onFlushCompleted fires once per flush with accurate keysCount and recordsCount'() {
        given:
        ResequenceEventListener listener = Mock()
        driver = new TopologyTestDriver(buildTopology(listener), driverConfig())
        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when:
        inputTopic.pipeInput('key1', new TestRecord('A', 1000L))
        inputTopic.pipeInput('key1', new TestRecord('B', 2000L))
        inputTopic.pipeInput('key2', new TestRecord('C', 3000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        1 * listener.onFlushCompleted(2, 3)
    }

    def 'onFlushCompleted reports zero totals when no records arrived between flushes'() {
        given:
        ResequenceEventListener listener = Mock()
        driver = new TopologyTestDriver(buildTopology(listener), driverConfig())
        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when: 'first flush with one record'
        inputTopic.pipeInput('key1', new TestRecord('A', 1000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        1 * listener.onFlushCompleted(1, 1)

        when: 'second flush with no new records'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        1 * listener.onFlushCompleted(0, 0)
    }

    def 'noOp listener causes no regressions — existing processor behaviour is unchanged'() {
        given:
        def topology = buildTopology(ResequenceEventListener.noOp())
        driver = new TopologyTestDriver(topology, driverConfig())
        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        when:
        inputTopic.pipeInput('key1', new TestRecord('B', 2000L))
        inputTopic.pipeInput('key1', new TestRecord('A', 1000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2
        results[0].value.type == 'A'
        results[1].value.type == 'B'
    }
}
