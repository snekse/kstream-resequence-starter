package com.snekse.kafka.streams.resequence.processor

import com.snekse.kafka.streams.resequence.domain.BufferedRecord
import com.snekse.kafka.streams.resequence.domain.ResequenceComparator
import com.snekse.kafka.streams.resequence.domain.TombstoneSortOrder
import com.snekse.kafka.streams.resequence.serde.BufferedRecordListSerde
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.state.Stores
import org.springframework.kafka.support.serializer.JacksonJsonSerde
import tools.jackson.databind.json.JsonMapper
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.time.Duration

class ResequenceProcessorSpec extends Specification {

    static final String SOURCE_TOPIC = 'input'
    static final String SINK_TOPIC = 'output'
    static final String STATE_STORE = 'resequence-store'
    static final Duration FLUSH_INTERVAL = Duration.ofMillis(100)

    @AutoCleanup
    TopologyTestDriver driver

    /**
     * Simple test record used in place of domain-specific types.
     */
    static class TestRecord {
        String type
        long ts

        TestRecord() {}
        TestRecord(String type, long ts) {
            this.type = type
            this.ts = ts
        }
    }

    /** Builds a comparator that orders by type priority (A < B < C), then by ts, with configurable tombstone position. */
    private static ResequenceComparator<TestRecord> comparatorWith(TombstoneSortOrder tombstoneOrder) {
        return { BufferedRecord<TestRecord> a, BufferedRecord<TestRecord> b ->
            def r1 = a.record
            def r2 = b.record
            if (r1 == null && r2 == null) return 0
            if (r1 == null) return tombstoneOrder.signum
            if (r2 == null) return -tombstoneOrder.signum

            def typePriority = [A: 0, B: 1, C: 2]
            int cmp = Integer.compare(
                typePriority.getOrDefault(r1.type, Integer.MAX_VALUE),
                typePriority.getOrDefault(r2.type, Integer.MAX_VALUE)
            )
            if (cmp != 0) return cmp
            return Long.compare(r1.ts, r2.ts)
        } as ResequenceComparator<TestRecord>
    }

    static final ResequenceComparator<TestRecord> TEST_COMPARATOR = comparatorWith(TombstoneSortOrder.LAST)

    private static Properties driverConfig() {
        def props = new Properties()
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, 'test-app')
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.name)
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.name)
        props
    }

    private static <K, KR> Topology buildTopology(
            Serde<K> keySerde,
            Serde<KR> outputKeySerde,
            ResequenceComparator<TestRecord> comparator,
            KeyMapper<K, KR> keyMapper,
            ValueMapper<KR, TestRecord, TestRecord> valueMapper) {

        def valueSerde = new JacksonJsonSerde<>(TestRecord)
        def bufferedSerde = new BufferedRecordListSerde<>(TestRecord, JsonMapper.builder().build())

        def topology = new Topology()
        topology.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE),
                keySerde,
                bufferedSerde))

        topology.addSource('source',
                keySerde.deserializer(),
                valueSerde.deserializer(),
                SOURCE_TOPIC)

        topology.addProcessor('resequencer',
                () -> new ResequenceProcessor<>(comparator, STATE_STORE, FLUSH_INTERVAL, keyMapper, valueMapper),
                'source')

        topology.connectProcessorAndStateStores('resequencer', STATE_STORE)

        topology.addSink('sink',
                SINK_TOPIC,
                outputKeySerde.serializer(),
                valueSerde.serializer(),
                'resequencer')

        topology
    }

    def 'should buffer and flush records sorted by comparator'() {
        given: 'a topology with String keys'
        def topology = buildTopology(Serdes.String(), Serdes.String(), TEST_COMPARATOR, null, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        and: 'out-of-order records'
        def baseTime = 1000L

        when: 'records are piped in out of order'
        inputTopic.pipeInput('key1', new TestRecord('C', baseTime + 2000))
        inputTopic.pipeInput('key1', new TestRecord('A', baseTime))
        inputTopic.pipeInput('key1', new TestRecord('B', baseTime + 1000))

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'output records are in correct order'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 3
        results[0].value.type == 'A'
        results[1].value.type == 'B'
        results[2].value.type == 'C'
    }

    def 'should re-key using key mapper'() {
        given: 'a topology with Long to String re-keying'
        KeyMapper<Long, String> keyMapper = { Long key -> key + '-mapped' }
        def topology = buildTopology(Serdes.Long(), Serdes.String(), TEST_COMPARATOR, keyMapper, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.Long().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        when: 'records are piped in'
        inputTopic.pipeInput(42L, new TestRecord('B', 2000L))
        inputTopic.pipeInput(42L, new TestRecord('A', 1000L))

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'output keys are mapped'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2
        results[0].key == '42-mapped'
        results[0].value.type == 'A'
        results[1].key == '42-mapped'
        results[1].value.type == 'B'
    }

    def 'should apply value mapper on non-null records'() {
        given: 'a topology with a value mapper'
        KeyMapper<String, String> keyMapper = { String key -> key + '-enriched' }
        ValueMapper<String, TestRecord, TestRecord> valueMapper = { String outputKey, BufferedRecord<TestRecord> buffered ->
            def record = buffered.record
            if (record != null) {
                record.type = record.type + ':' + outputKey
            }
            record
        }
        def topology = buildTopology(Serdes.String(), Serdes.String(), TEST_COMPARATOR, keyMapper, valueMapper)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        when: 'a record and tombstone are piped in'
        inputTopic.pipeInput('k1', new TestRecord('A', 1000L))
        inputTopic.pipeInput('k1', null as TestRecord)

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'non-null record is enriched'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2
        results[0].value.type == 'A:k1-enriched'

        and: 'tombstone does not cause NPE'
        results[1].value == null
    }

    def 'should skip records with null keys'() {
        given: 'a topology with String keys'
        def topology = buildTopology(Serdes.String(), Serdes.String(), TEST_COMPARATOR, null, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        when: 'records with null and non-null keys are piped in'
        inputTopic.pipeInput(null as String, new TestRecord('A', 1000L))
        inputTopic.pipeInput('valid', new TestRecord('B', 2000L))
        inputTopic.pipeInput(null as String, new TestRecord('C', 3000L))

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'only the record with non-null key is output'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 1
        results[0].key == 'valid'
        results[0].value.type == 'B'
    }

    def 'should position tombstone #position with TombstoneSortOrder.#order'() {
        given: 'a topology using a comparator configured with #order'
        def comparator = comparatorWith(order)
        def topology = buildTopology(Serdes.String(), Serdes.String(), comparator, null, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        when: 'a tombstone and normal record are piped in'
        inputTopic.pipeInput('k1', null as TestRecord)
        inputTopic.pipeInput('k1', new TestRecord('A', 1000L))

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'both records are forwarded'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2

        and: 'tombstone is positioned correctly'
        (results[tombstoneIndex].value == null) == true
        results[normalIndex].value.type == 'A'

        where:
        order                    | position | tombstoneIndex | normalIndex
        TombstoneSortOrder.LAST  | 'last'   | 1              | 0
        TombstoneSortOrder.FIRST | 'first'  | 0              | 1
    }

    def 'should treat tombstone as equal to normal record with TombstoneSortOrder.EQUAL'() {
        given: 'a topology using a comparator configured with EQUAL'
        def comparator = comparatorWith(TombstoneSortOrder.EQUAL)

        expect: 'comparing a tombstone to a normal record returns 0'
        def normal = BufferedRecord.<TestRecord>builder().record(new TestRecord('A', 1000L)).build()
        def tombstone = BufferedRecord.<TestRecord>builder().record(null).build()
        comparator.compare(tombstone, normal) == 0
        comparator.compare(normal, tombstone) == 0
    }

    def 'should only flush records received since last flush cycle'() {
        given: 'a topology with String keys'
        def topology = buildTopology(Serdes.String(), Serdes.String(), TEST_COMPARATOR, null, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        when: 'records are sent for key1 and key2 in the first cycle'
        inputTopic.pipeInput('key1', new TestRecord('B', 2000L))
        inputTopic.pipeInput('key1', new TestRecord('A', 1000L))
        inputTopic.pipeInput('key2', new TestRecord('C', 3000L))

        and: 'first flush triggers'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'all 3 records are flushed'
        def firstResults = outputTopic.readKeyValuesToList()
        firstResults.size() == 3

        when: 'only key2 receives new records in the second cycle'
        inputTopic.pipeInput('key2', new TestRecord('B', 5000L))
        inputTopic.pipeInput('key2', new TestRecord('A', 4000L))

        and: 'second flush triggers'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'only key2 records are emitted, sorted correctly'
        def secondResults = outputTopic.readKeyValuesToList()
        secondResults.size() == 2
        secondResults.every { it.key == 'key2' }
        secondResults[0].value.type == 'A'
        secondResults[1].value.type == 'B'
    }

    def 'should produce no output when no records arrive between flushes'() {
        given: 'a topology with String keys'
        def topology = buildTopology(Serdes.String(), Serdes.String(), TEST_COMPARATOR, null, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        when: 'a record is sent and first flush triggers'
        inputTopic.pipeInput('key1', new TestRecord('A', 1000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'first flush emits the record'
        outputTopic.readKeyValuesToList().size() == 1

        when: 'no records are sent and second flush triggers'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'no output is produced'
        outputTopic.readKeyValuesToList().isEmpty()
    }

    def 'should correctly flush across multiple cycles with different keys'() {
        given: 'a topology with String keys'
        def topology = buildTopology(Serdes.String(), Serdes.String(), TEST_COMPARATOR, null, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(TestRecord).deserializer())

        when: 'cycle 1: key1 gets out-of-order records'
        inputTopic.pipeInput('key1', new TestRecord('C', 3000L))
        inputTopic.pipeInput('key1', new TestRecord('A', 1000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'cycle 1 output is sorted'
        def cycle1 = outputTopic.readKeyValuesToList()
        cycle1.size() == 2
        cycle1[0].value.type == 'A'
        cycle1[1].value.type == 'C'

        when: 'cycle 2: key2 and key3 get records'
        inputTopic.pipeInput('key2', new TestRecord('B', 2000L))
        inputTopic.pipeInput('key3', new TestRecord('A', 1000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'cycle 2 output contains both keys'
        def cycle2 = outputTopic.readKeyValuesToList()
        cycle2.size() == 2
        cycle2.collect { it.key }.toSet() == ['key2', 'key3'] as Set

        when: 'cycle 3: key1 returns with new records'
        inputTopic.pipeInput('key1', new TestRecord('B', 5000L))
        inputTopic.pipeInput('key1', new TestRecord('A', 4000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'cycle 3 output is only key1, sorted'
        def cycle3 = outputTopic.readKeyValuesToList()
        cycle3.size() == 2
        cycle3.every { it.key == 'key1' }
        cycle3[0].value.type == 'A'
        cycle3[1].value.type == 'B'
    }
}
