package com.example.sampleapp.processor

import com.example.sampleapp.config.TombstoneSortOrder
import com.example.sampleapp.domain.BufferedRecord
import com.example.sampleapp.domain.EntityType
import com.example.sampleapp.domain.ResequenceComparator
import com.example.sampleapp.domain.SampleRecord
import com.example.sampleapp.serde.BufferedRecordListSerde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TestOutputTopic
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.state.Stores
import org.springframework.kafka.support.serializer.JacksonJsonSerde
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.time.Duration
import java.time.Instant

class ResequenceProcessorSpec extends Specification {

    static final String SOURCE_TOPIC = 'input'
    static final String SINK_TOPIC = 'output'
    static final String STATE_STORE = 'resequence-store'
    static final Duration FLUSH_INTERVAL = Duration.ofMillis(100)

    @AutoCleanup
    TopologyTestDriver driver

    private static SampleRecord buildRecord(Long clientId, String operationType, Long timestamp) {
        SampleRecord.builder()
                .clientId(clientId)
                .operationType(operationType)
                .timestamp(timestamp)
                .entityType(EntityType.Parent)
                .transactionId(UUID.randomUUID())
                .build()
    }

    private static Properties driverConfig() {
        def props = new Properties()
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, 'test-app')
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.LongSerde.name)
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.name)
        props
    }

    private static <K, KR> Topology buildTopology(
            org.apache.kafka.common.serialization.Serde<K> keySerde,
            org.apache.kafka.common.serialization.Serde<KR> outputKeySerde,
            Comparator<BufferedRecord<SampleRecord>> comparator,
            KeyMapper<K, KR> keyMapper,
            java.util.function.BiConsumer<KR, SampleRecord> valueEnricher) {

        def valueSerde = new JacksonJsonSerde<>(SampleRecord)
        def bufferedSerde = new BufferedRecordListSerde<>(SampleRecord)

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
                () -> new ResequenceProcessor<>(comparator, STATE_STORE, FLUSH_INTERVAL, keyMapper, valueEnricher),
                'source')

        topology.connectProcessorAndStateStores('resequencer', STATE_STORE)

        topology.addSink('sink',
                SINK_TOPIC,
                outputKeySerde.serializer(),
                valueSerde.serializer(),
                'resequencer')

        topology
    }

    def 'should resequence with Long keys and identity key mapper (no re-keying)'() {
        given: 'a topology with Long input and output keys, no key mapper'
        def comparator = new ResequenceComparator(TombstoneSortOrder.LAST)
        def topology = buildTopology(Serdes.Long(), Serdes.Long(), comparator, null, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.Long().serializer(), new JacksonJsonSerde<>(SampleRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.Long().deserializer(), new JacksonJsonSerde<>(SampleRecord).deserializer())

        and: 'out-of-order records'
        def baseTime = System.currentTimeMillis()
        def delete = buildRecord(1001L, 'DELETE', baseTime + 2000)
        def create = buildRecord(1001L, 'CREATE', baseTime)
        def update = buildRecord(1001L, 'UPDATE', baseTime + 1000)

        when: 'records are piped in out of order'
        inputTopic.pipeInput(1001L, delete)
        inputTopic.pipeInput(1001L, update)
        inputTopic.pipeInput(1001L, create)

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'output records are in correct order with Long keys preserved'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 3
        results[0].key == 1001L
        results[0].value.operationType == 'CREATE'
        results[1].key == 1001L
        results[1].value.operationType == 'UPDATE'
        results[2].key == 1001L
        results[2].value.operationType == 'DELETE'
    }

    def 'should resequence with Long input keys and String output keys via key mapper'() {
        given: 'a topology with Long to String re-keying'
        def comparator = new ResequenceComparator(TombstoneSortOrder.LAST)
        KeyMapper<Long, String> keyMapper = { Long key -> key + '-sorted' }
        def topology = buildTopology(Serdes.Long(), Serdes.String(), comparator, keyMapper, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.Long().serializer(), new JacksonJsonSerde<>(SampleRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(SampleRecord).deserializer())

        and: 'out-of-order records'
        def baseTime = System.currentTimeMillis()

        when: 'records are piped in'
        inputTopic.pipeInput(42L, buildRecord(42L, 'DELETE', baseTime + 2000))
        inputTopic.pipeInput(42L, buildRecord(42L, 'CREATE', baseTime))

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'output keys are mapped Strings'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2
        results[0].key == '42-sorted'
        results[0].value.operationType == 'CREATE'
        results[1].key == '42-sorted'
        results[1].value.operationType == 'DELETE'
    }

    def 'should resequence with String input keys and no re-keying'() {
        given: 'a topology with String keys, no key mapper'
        def comparator = new ResequenceComparator(TombstoneSortOrder.LAST)
        def topology = buildTopology(Serdes.String(), Serdes.String(), comparator, null, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(SampleRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(SampleRecord).deserializer())

        and: 'records with String keys'
        def baseTime = System.currentTimeMillis()

        when: 'records are piped in out of order'
        inputTopic.pipeInput('entity-abc', buildRecord(1L, 'UPDATE', baseTime + 1000))
        inputTopic.pipeInput('entity-abc', buildRecord(1L, 'CREATE', baseTime))

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'output has String keys and records sorted by timestamp'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2
        results[0].key == 'entity-abc'
        results[0].value.operationType == 'CREATE'
        results[1].key == 'entity-abc'
        results[1].value.operationType == 'UPDATE'
    }

    def 'should resequence with Integer keys and Long output keys'() {
        given: 'a topology with Integer to Long re-keying'
        def comparator = new ResequenceComparator(TombstoneSortOrder.LAST)
        KeyMapper<Integer, Long> keyMapper = { Integer key -> key.toLong() * 1000L }
        def topology = buildTopology(Serdes.Integer(), Serdes.Long(), comparator, keyMapper, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.Integer().serializer(), new JacksonJsonSerde<>(SampleRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.Long().deserializer(), new JacksonJsonSerde<>(SampleRecord).deserializer())

        and: 'records with Integer keys'
        def baseTime = System.currentTimeMillis()

        when: 'records are piped in'
        inputTopic.pipeInput(7, buildRecord(7L, 'UPDATE', baseTime + 1000))
        inputTopic.pipeInput(7, buildRecord(7L, 'CREATE', baseTime))

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'output keys are mapped Longs'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2
        results[0].key == 7000L
        results[1].key == 7000L
        results[0].value.operationType == 'CREATE'
        results[1].value.operationType == 'UPDATE'
    }

    def 'should invoke value enricher callback when provided'() {
        given: 'a topology with a value enricher that sets newKey'
        def comparator = new ResequenceComparator(TombstoneSortOrder.LAST)
        KeyMapper<Long, String> keyMapper = { Long key -> key + '-enriched' }
        java.util.function.BiConsumer<String, SampleRecord> enricher = { String newKey, SampleRecord record ->
            record.setNewKey(newKey)
        }
        def topology = buildTopology(Serdes.Long(), Serdes.String(), comparator, keyMapper, enricher)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.Long().serializer(), new JacksonJsonSerde<>(SampleRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(SampleRecord).deserializer())

        and: 'records including a tombstone'
        def baseTime = System.currentTimeMillis()

        when: 'records are piped in including a tombstone'
        inputTopic.pipeInput(99L, buildRecord(99L, 'CREATE', baseTime))
        inputTopic.pipeInput(99L, null as SampleRecord) // tombstone

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'non-null records have newKey enriched'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 2

        and: 'the non-null record has enriched newKey'
        results[0].value.newKey == '99-enriched'

        and: 'the tombstone does not cause NPE (null value is forwarded)'
        results[1].value == null
    }

    def 'should not invoke value enricher when not provided'() {
        given: 'a topology without a value enricher'
        def comparator = new ResequenceComparator(TombstoneSortOrder.LAST)
        KeyMapper<Long, String> keyMapper = { Long key -> key + '-mapped' }
        def topology = buildTopology(Serdes.Long(), Serdes.String(), comparator, keyMapper, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.Long().serializer(), new JacksonJsonSerde<>(SampleRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(SampleRecord).deserializer())

        when: 'a record is piped in'
        inputTopic.pipeInput(50L, buildRecord(50L, 'CREATE', System.currentTimeMillis()))

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'record is forwarded without newKey enrichment'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 1
        results[0].key == '50-mapped'
        results[0].value.newKey == null
    }

    def 'should handle null keys with generic key types'() {
        given: 'a topology with String keys'
        def comparator = new ResequenceComparator(TombstoneSortOrder.LAST)
        def topology = buildTopology(Serdes.String(), Serdes.String(), comparator, null, null)
        driver = new TopologyTestDriver(topology, driverConfig())

        def inputTopic = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(SampleRecord).serializer())
        def outputTopic = driver.createOutputTopic(SINK_TOPIC,
                Serdes.String().deserializer(), new JacksonJsonSerde<>(SampleRecord).deserializer())

        when: 'records with null and non-null keys are piped in'
        inputTopic.pipeInput(null as String, buildRecord(1L, 'UPDATE', System.currentTimeMillis()))
        inputTopic.pipeInput('valid-key', buildRecord(2L, 'CREATE', System.currentTimeMillis()))
        inputTopic.pipeInput(null as String, buildRecord(3L, 'DELETE', System.currentTimeMillis()))

        and: 'wall clock advances to trigger flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then: 'only the record with non-null key is output'
        def results = outputTopic.readKeyValuesToList()
        results.size() == 1
        results[0].key == 'valid-key'
        results[0].value.operationType == 'CREATE'
    }
}
