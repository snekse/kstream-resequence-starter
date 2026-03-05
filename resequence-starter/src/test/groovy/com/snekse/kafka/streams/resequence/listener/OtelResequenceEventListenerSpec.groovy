package com.snekse.kafka.streams.resequence.listener

import com.snekse.kafka.streams.resequence.domain.BufferedRecord
import com.snekse.kafka.streams.resequence.domain.ResequenceComparator
import com.snekse.kafka.streams.resequence.domain.TombstoneSortOrder
import com.snekse.kafka.streams.resequence.processor.ResequenceProcessor
import com.snekse.kafka.streams.resequence.serde.BufferedRecordListSerde
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
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

class OtelResequenceEventListenerSpec extends Specification {

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

    InMemoryMetricReader metricReader
    OtelResequenceEventListener otelListener

    @AutoCleanup
    TopologyTestDriver driver

    def setup() {
        metricReader = InMemoryMetricReader.create()
        def meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build()
        def openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build()
        otelListener = new OtelResequenceEventListener(openTelemetry)
    }

    private Topology buildTopology() {
        def valueSerde = new JacksonJsonSerde<>(TestRecord)
        def bufferedSerde = new BufferedRecordListSerde<>(TestRecord, JsonMapper.builder().build())
        def topology = new Topology()
        topology.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE), Serdes.String(), bufferedSerde))
        topology.addSource('source', Serdes.String().deserializer(), valueSerde.deserializer(), SOURCE_TOPIC)
        topology.addProcessor('resequencer',
                () -> new ResequenceProcessor<>(TEST_COMPARATOR, STATE_STORE, FLUSH_INTERVAL, null, null, otelListener),
                'source')
        topology.connectProcessorAndStateStores('resequencer', STATE_STORE)
        topology.addSink('sink', SINK_TOPIC, Serdes.String().serializer(), valueSerde.serializer(), 'resequencer')
        topology
    }

    private static Properties driverConfig() {
        def props = new Properties()
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, 'test-app')
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.name)
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.name)
        props
    }

    /** Find the sum of all data points for a counter metric by name. */
    private long counterValue(String metricName) {
        Collection<MetricData> metrics = metricReader.collectAllMetrics()
        def metric = metrics.find { it.name == metricName }
        if (metric == null) return 0L
        metric.longSumData.points.sum { LongPointData p -> p.value } as long
    }

    /** Find all data point values for a histogram metric by name. */
    private List<Long> histogramCounts(String metricName) {
        Collection<MetricData> metrics = metricReader.collectAllMetrics()
        def metric = metrics.find { it.name == metricName }
        if (metric == null) return []
        metric.histogramData.points.collect { it.count } as List<Long>
    }

    def 'records.buffered counter increments for each non-tombstone record'() {
        given:
        driver = new TopologyTestDriver(buildTopology(), driverConfig())
        def input = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when:
        input.pipeInput('k1', new TestRecord('A', 1000L))
        input.pipeInput('k1', new TestRecord('B', 2000L))
        input.pipeInput('k2', new TestRecord('C', 3000L))

        then:
        counterValue('resequence.records.buffered') == 3
        counterValue('resequence.tombstones.received') == 0
    }

    def 'tombstones.received and records.buffered both increment for null-value records'() {
        given:
        driver = new TopologyTestDriver(buildTopology(), driverConfig())
        def input = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when:
        input.pipeInput('k1', null as TestRecord)

        then:
        counterValue('resequence.tombstones.received') == 1
        counterValue('resequence.records.buffered') == 1
    }

    def 'records.ignored counter increments for null-key records; buffered stays zero'() {
        given:
        driver = new TopologyTestDriver(buildTopology(), driverConfig())
        def input = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when:
        input.pipeInput(null as String, new TestRecord('A', 1000L))

        then:
        counterValue('resequence.records.ignored') == 1
        counterValue('resequence.records.buffered') == 0
    }

    def 'flushes counter increments once per flush cycle; first flush has full_scan=true'() {
        given:
        driver = new TopologyTestDriver(buildTopology(), driverConfig())
        def input = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        input.pipeInput('k1', new TestRecord('A', 1000L))

        when: 'first flush'
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        def metrics = metricReader.collectAllMetrics()
        def flushMetric = metrics.find { it.name == 'resequence.flushes' }
        flushMetric != null
        def points = flushMetric.longSumData.points
        points.size() == 1
        points[0].value == 1
        points[0].attributes.get(AttributeKey.booleanKey('full_scan')) == true

        when: 'second flush'
        input.pipeInput('k1', new TestRecord('B', 2000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        def metrics2 = metricReader.collectAllMetrics()
        def flushMetric2 = metrics2.find { it.name == 'resequence.flushes' }
        def byFullScan = flushMetric2.longSumData.points.groupBy {
            it.attributes.get(AttributeKey.booleanKey('full_scan'))
        }
        byFullScan[true][0].value == 1
        byFullScan[false][0].value == 1
    }

    def 'flush.duration histogram records one entry per flush'() {
        given:
        driver = new TopologyTestDriver(buildTopology(), driverConfig())
        def input = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())
        input.pipeInput('k1', new TestRecord('A', 1000L))

        when:
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        def counts = histogramCounts('resequence.flush.duration')
        counts.size() == 1
        counts[0] == 1  // one data point recorded for this flush
    }

    def 'buffer.size histogram records per-key record count at flush time'() {
        given:
        driver = new TopologyTestDriver(buildTopology(), driverConfig())
        def input = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when: '3 records buffered under same key, then flushed'
        input.pipeInput('k1', new TestRecord('A', 1000L))
        input.pipeInput('k1', new TestRecord('B', 2000L))
        input.pipeInput('k1', new TestRecord('C', 3000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        def metrics = metricReader.collectAllMetrics()
        def bufferMetric = metrics.find { it.name == 'resequence.buffer.size' }
        bufferMetric != null
        def point = bufferMetric.histogramData.points[0]
        point.sum == 3  // one key flushed with 3 records → sum == 3
    }

    def 'records.forwarded counter equals records buffered after flush'() {
        given:
        driver = new TopologyTestDriver(buildTopology(), driverConfig())
        def input = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when:
        input.pipeInput('k1', new TestRecord('A', 1000L))
        input.pipeInput('k2', new TestRecord('B', 2000L))
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        counterValue('resequence.records.forwarded') == 2
    }

    def 'accumulator getters match OTel counter values'() {
        given:
        driver = new TopologyTestDriver(buildTopology(), driverConfig())
        def input = driver.createInputTopic(SOURCE_TOPIC,
                Serdes.String().serializer(), new JacksonJsonSerde<>(TestRecord).serializer())

        when:
        input.pipeInput('k1', new TestRecord('A', 1000L))
        input.pipeInput('k1', null as TestRecord)          // tombstone
        input.pipeInput(null as String, new TestRecord('B', 2000L))  // ignored
        driver.advanceWallClockTime(FLUSH_INTERVAL)

        then:
        otelListener.totalRecordsBuffered == counterValue('resequence.records.buffered')
        otelListener.totalTombstonesReceived == counterValue('resequence.tombstones.received')
        otelListener.totalRecordsIgnored == counterValue('resequence.records.ignored')
        otelListener.totalRecordsForwarded == counterValue('resequence.records.forwarded')
        otelListener.totalFlushes == 1
    }

    def 'OtelResequenceEventListener constructed with GlobalOpenTelemetry noop does not throw'() {
        when: 'use the noop GlobalOpenTelemetry (no SDK on classpath needed)'
        def noopListener = new OtelResequenceEventListener(GlobalOpenTelemetry.get())
        noopListener.onRecordIgnored()
        noopListener.onFlushStarted(true)
        noopListener.onFlushCompleted(0, 0)

        then:
        noExceptionThrown()
    }
}
