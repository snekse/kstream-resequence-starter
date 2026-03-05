# Implementation Spec: Resequence Observability - Phase 2

**Contract**: ./contract.md
**Estimated Effort**: M

## Technical Approach

This phase adds the OTel implementation and wires everything into auto-configuration and the sample-app. Three independent concerns:

1. **OTel implementation** — `OtelResequenceEventListener` in `resequence-starter` uses `opentelemetry-api` (`compileOnly`) to emit counters, histograms, and a gauge. The class also maintains simple `AtomicLong` accumulators alongside the OTel instruments so the sample-app can log totals on shutdown without needing an OTel metric reader.

2. **Auto-configuration** — `ResequenceAutoConfiguration` gets a `@ConditionalOnClass(OpenTelemetry.class)` + `@ConditionalOnMissingBean(ResequenceEventListener.class)` bean that auto-registers the OTel listener when the OTel API is on the runtime classpath. An `otel.enabled` flag in `ResequenceProperties` allows opt-out.

3. **Sample-app wiring** — add OTel SDK dependencies, inject the auto-configured listener into `ResequenceTopologyConfig`, and add a `MetricsShutdownLogger` that logs accumulated totals on `ContextClosedEvent`.

The `opentelemetry-api` is `compileOnly` in `resequence-starter` — it must not appear in the published `api` or `implementation` configurations. Users who want OTel metrics add the OTel API (or SDK) to their own runtime classpath. Users who don't bring OTel get the no-op listener automatically.

## Feedback Strategy

**Inner-loop command**: `./gradlew :resequence-starter:test`

**Playground**: Test suite — `OtelResequenceEventListenerSpec` in the **`resequence-starter` module** (not sample-app) uses OTel's `InMemoryMetricReader` to assert metric values after processor operations. OTel metric correctness is verified entirely within the library module; the sample-app only demonstrates runtime wiring and shutdown logging.

**Why this approach**: The OTel SDK testing module provides a synchronous in-memory reader that returns all collected metrics on demand — ideal for Spock `then:` blocks with precise assertions. Keeping these tests in `resequence-starter` means the library self-validates its own instrumentation without depending on the sample-app.

## File Changes

### New Files

| File Path | Purpose |
| --- | --- |
| `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/listener/OtelResequenceEventListener.java` | OTel implementation of `ResequenceEventListener`; emits counters, histograms, and accumulates totals |
| `resequence-starter/src/test/groovy/com/snekse/kafka/streams/resequence/listener/OtelResequenceEventListenerSpec.groovy` | Spock spec using `InMemoryMetricReader` to assert OTel metric values |
| `sample-app/src/main/java/com/example/sampleapp/observability/MetricsShutdownLogger.java` | `ApplicationListener<ContextClosedEvent>` that logs metric totals from `OtelResequenceEventListener` on shutdown |

### Modified Files

| File Path | Changes |
| --- | --- |
| `resequence-starter/build.gradle.kts` | Add `compileOnly("io.opentelemetry:opentelemetry-api")` and `testImplementation("io.opentelemetry:opentelemetry-sdk-testing")` |
| `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/config/ResequenceProperties.java` | Add nested `OtelProperties` inner class with `enabled` boolean; expose via `getOtel()` |
| `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/config/ResequenceAutoConfiguration.java` | Add OTel conditional bean for `OtelResequenceEventListener`; fallback no-op bean |
| `sample-app/build.gradle.kts` | Add OTel SDK + logging exporter dependencies |
| `sample-app/src/main/java/com/example/sampleapp/config/ResequenceTopologyConfig.java` | Inject `ResequenceEventListener` bean; pass it to `ResequenceProcessor` constructor |

## Implementation Details

### OtelResequenceEventListener

**Pattern to follow**: `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/listener/ResequenceEventListener.java` (implements the interface from Phase 1)

**Overview**: Wraps OTel API instruments. All instruments are created once in the constructor from the provided `OpenTelemetry` instance. Flush duration timing uses a simple long field — safe because KStreams processors are single-threaded.

```java
package com.snekse.kafka.streams.resequence.listener;

import com.snekse.kafka.streams.resequence.domain.BufferedRecord;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;

import java.util.concurrent.atomic.AtomicLong;

public class OtelResequenceEventListener implements ResequenceEventListener {

    private static final String INSTRUMENTATION_NAME = "com.snekse.kafka.streams.resequence";
    private static final AttributeKey<Boolean> FULL_SCAN = AttributeKey.booleanKey("full_scan");

    // OTel instruments
    private final LongCounter recordsBuffered;
    private final LongCounter tombstonesReceived;
    private final LongCounter recordsIgnored;
    private final LongCounter recordsForwarded;
    private final LongCounter flushCount;
    private final LongHistogram flushDurationMs;
    private final LongHistogram bufferSize;

    // Simple accumulators for shutdown summary logging
    private final AtomicLong totalRecordsBuffered = new AtomicLong();
    private final AtomicLong totalTombstonesReceived = new AtomicLong();
    private final AtomicLong totalRecordsIgnored = new AtomicLong();
    private final AtomicLong totalRecordsForwarded = new AtomicLong();
    private final AtomicLong totalFlushes = new AtomicLong();

    // Flush timing and scan state (single-threaded — KStreams processors are not concurrent)
    private long flushStartNanos;
    private boolean lastFlushWasFullScan;

    public OtelResequenceEventListener(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_NAME);

        recordsBuffered = meter.counterBuilder("resequence.records.buffered")
                .setDescription("Number of records appended to the resequence buffer (includes tombstones)")
                .setUnit("{record}")
                .build();

        tombstonesReceived = meter.counterBuilder("resequence.tombstones.received")
                .setDescription("Number of tombstone (null-value) records buffered")
                .setUnit("{record}")
                .build();

        recordsIgnored = meter.counterBuilder("resequence.records.ignored")
                .setDescription("Number of records silently ignored due to null key")
                .setUnit("{record}")
                .build();

        recordsForwarded = meter.counterBuilder("resequence.records.forwarded")
                .setDescription("Number of records sorted and forwarded downstream")
                .setUnit("{record}")
                .build();

        flushCount = meter.counterBuilder("resequence.flushes")
                .setDescription("Number of flush cycles completed")
                .setUnit("{flush}")
                .build();

        flushDurationMs = meter.histogramBuilder("resequence.flush.duration")
                .ofLongs()
                .setDescription("Duration of each flush cycle in milliseconds")
                .setUnit("ms")
                .build();

        bufferSize = meter.histogramBuilder("resequence.buffer.size")
                .ofLongs()
                .setDescription("Number of records per key at flush time")
                .setUnit("{record}")
                .build();
    }

    @Override
    public void onRecordBuffered(Object key, BufferedRecord<?> record) {
        recordsBuffered.add(1);
        totalRecordsBuffered.incrementAndGet();
    }

    @Override
    public void onTombstoneReceived(Object key, BufferedRecord<?> record) {
        tombstonesReceived.add(1);
        totalTombstonesReceived.incrementAndGet();
    }

    @Override
    public void onRecordIgnored() {
        recordsIgnored.add(1);
        totalRecordsIgnored.incrementAndGet();
    }

    @Override
    public void onFlushStarted(boolean isFullScan) {
        this.lastFlushWasFullScan = isFullScan; // stored for use in onFlushCompleted
        flushStartNanos = System.nanoTime();
    }

    @Override
    public void onFlushCompleted(int keysCount, int recordsCount) {
        long durationMs = (System.nanoTime() - flushStartNanos) / 1_000_000;
        flushDurationMs.record(durationMs);
        flushCount.add(1, Attributes.of(FULL_SCAN, lastFlushWasFullScan));
        totalRecordsForwarded.addAndGet(recordsCount);
        totalFlushes.incrementAndGet();
    }

    @Override
    public void onKeyFlushed(Object key, int recordCount) {
        recordsForwarded.add(recordCount);
        bufferSize.record(recordCount);
    }

    // Accessors for shutdown summary logging
    public long getTotalRecordsBuffered() { return totalRecordsBuffered.get(); }
    public long getTotalTombstonesReceived() { return totalTombstonesReceived.get(); }
    public long getTotalRecordsIgnored() { return totalRecordsIgnored.get(); }
    public long getTotalRecordsForwarded() { return totalRecordsForwarded.get(); }
    public long getTotalFlushes() { return totalFlushes.get(); }
}
```

**Key decisions**:
- `AtomicLong` accumulators alongside OTel instruments — this lets `MetricsShutdownLogger` log totals without needing an OTel `MetricReader` in the sample-app
- `flushStartNanos` is a plain field (not AtomicLong) — KStreams processor threads are single-threaded per task; this is safe
- `INSTRUMENTATION_NAME` matches the library's root package for conventional OTel naming
- `lastFlushWasFullScan` is stored in `onFlushStarted` and read in `onFlushCompleted` to attach the `full_scan` attribute to the flush counter — since the two callbacks are paired and KStreams is single-threaded, a plain field is safe

**Implementation steps**:

1. Add OTel `compileOnly` dep to `resequence-starter/build.gradle.kts` (see build changes below) and sync
2. Create `OtelResequenceEventListener.java` in the `listener` package
3. Verify `compileOnly` means OTel classes are present at compile time: `./gradlew :resequence-starter:compileJava`
4. Confirm the starter JAR does NOT include OTel classes (only the interface usage): `./gradlew :resequence-starter:jar && jar tf resequence-starter/build/libs/*.jar | grep opentelemetry` — should return nothing

**Feedback loop**:
- **Playground**: `OtelResequenceEventListenerSpec` (set up in next component)
- **Experiment**: Send N records → assert counter value equals N
- **Check command**: `./gradlew :resequence-starter:test --tests "*.OtelResequenceEventListenerSpec"`

---

### OtelResequenceEventListenerSpec

**Location**: `resequence-starter/src/test/groovy/com/snekse/kafka/streams/resequence/listener/OtelResequenceEventListenerSpec.groovy`
— this is a **`resequence-starter` module test**, not a sample-app test. The `opentelemetry-sdk-testing` dependency is added to `resequence-starter`'s `testImplementation` configuration for exactly this purpose.

**Pattern to follow**: `resequence-starter/src/test/groovy/.../processor/ResequenceProcessorSpec.groovy` (TopologyTestDriver setup)

**Overview**: Integration test that wires a real `OtelResequenceEventListener` (backed by `InMemoryMetricReader`) into a processor topology, runs records through it, and asserts on collected metric values. All OTel correctness verification happens here — the sample-app is not used for metric assertions.

```groovy
package com.snekse.kafka.streams.resequence.listener

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import spock.lang.Specification

class OtelResequenceEventListenerSpec extends Specification {

    InMemoryMetricReader metricReader
    OtelResequenceEventListener otelListener
    // TopologyTestDriver setup (same pattern as ResequenceProcessorSpec)

    def setup() {
        metricReader = InMemoryMetricReader.create()
        def meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build()
        def openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build()
        otelListener = new OtelResequenceEventListener(openTelemetry)
        // build topology driver with otelListener injected
    }

    def "records buffered counter increments for each valid record"() {
        when:
        // send 3 records through topology
        then:
        def metrics = metricReader.collectAllMetrics()
        def buffered = findCounter(metrics, "resequence.records.buffered")
        buffered.value == 3
    }

    def "null key drop counter increments and records are not buffered"() { ... }
    def "flush duration histogram records a value after flush"() { ... }
    def "buffer size histogram records per-key record counts"() { ... }
    def "records forwarded counter equals total records sent (no nulls)"() { ... }
    def "flush count increments each flush cycle"() { ... }
    def "accumulator getters match counter values"() { ... }
}
```

**Key decisions**:
- Use `InMemoryMetricReader.create()` (synchronous, no scheduled collection) — `collectAllMetrics()` is called on demand in `then:` blocks
- Assert on the `InMemoryMetricReader` output, not `AtomicLong` accumulators, for OTel correctness; separately assert accumulators match (one test)
- Advance topology wall clock past flush interval to trigger flush before asserting flush metrics

**Implementation steps**:

1. Add `testImplementation("io.opentelemetry:opentelemetry-sdk-testing")` to `resequence-starter/build.gradle.kts`
2. Create `OtelResequenceEventListenerSpec.groovy`
3. Wire topology using `TopologyTestDriver` with `otelListener` passed to 6-arg `ResequenceProcessor` constructor
4. Implement metric lookup helper (find metric by name from `Collection<MetricData>`)
5. Write test cases listed above
6. Run: `./gradlew :resequence-starter:test`

**Feedback loop**:
- **Playground**: `./gradlew :resequence-starter:test --tests "*.OtelResequenceEventListenerSpec"`
- **Experiment**: Vary record count and assert counter equals that count; advance clock once and twice to check flush count
- **Check command**: `./gradlew :resequence-starter:test --tests "*.OtelResequenceEventListenerSpec"`

---

### Build Dependencies

**resequence-starter/build.gradle.kts** — add to `dependencies` block:

```kotlin
// OTel API — compileOnly so users are NOT forced to bring it transitively
compileOnly("io.opentelemetry:opentelemetry-api")

// OTel SDK testing utilities — only needed in tests
testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
```

**Note**: Do NOT use `implementation` or `api` — those would leak OTel onto users' compile and runtime classpaths. `compileOnly` keeps it strictly at compile time for this module.

**Note on version**: Spring Boot 4.x dependency management may or may not include OTel API. Check if `./gradlew :resequence-starter:dependencies` resolves `opentelemetry-api` from the Spring BOM. If not, pin an explicit version (e.g., `"io.opentelemetry:opentelemetry-api:1.40.0"`) — check Maven Central for the latest stable 1.x release at implementation time.

---

### ResequenceProperties — OTel Nested Config

**Pattern to follow**: Existing `ResequenceProperties.java` constructor-injection style.

**Overview**: Add a nested `OtelProperties` class that maps to `resequence.otel.*` properties. The main class gains an `otel` field with a `getOtel()` accessor.

```java
// New nested class (static inner class of ResequenceProperties)
public static class OtelProperties {
    private final boolean enabled;

    public OtelProperties(Boolean enabled) {
        this.enabled = enabled != null ? enabled : true;
    }

    public boolean isEnabled() {
        return enabled;
    }
}

// Updated ResequenceProperties constructor
public ResequenceProperties(String stateStoreName, Duration flushInterval,
                            TombstoneSortOrder tombstoneSortOrder,
                            OtelProperties otel) {
    this.stateStoreName = stateStoreName != null ? stateStoreName : "resequence-buffer";
    this.flushInterval = flushInterval != null ? flushInterval : Duration.ofSeconds(2);
    this.tombstoneSortOrder = tombstoneSortOrder != null ? tombstoneSortOrder : TombstoneSortOrder.LAST;
    this.otel = otel != null ? otel : new OtelProperties(null);
}

public OtelProperties getOtel() { return otel; }
```

**Key decisions**:
- Nested class maps `resequence.otel.enabled=false` naturally via Spring Boot's nested config binding
- Default is `enabled = true` (opt-out, not opt-in) — consistent with Spring Boot auto-configuration convention
- The outer constructor gains a new `OtelProperties otel` parameter; Spring Boot's `@ConfigurationProperties` constructor binding handles the nested object automatically

**Implementation steps**:
1. Add `private final OtelProperties otel;` field to `ResequenceProperties`
2. Add `OtelProperties` static inner class
3. Add `otel` parameter to the main constructor with null-coalescing
4. Add `getOtel()` accessor
5. Confirm: `./gradlew :resequence-starter:compileJava`

---

### ResequenceAutoConfiguration — OTel Conditional Bean

**Pattern to follow**: `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/config/ResequenceAutoConfiguration.java`

**Overview**: Register an `OtelResequenceEventListener` bean when OTel API is on the classpath and `resequence.otel.enabled` is true. Fall back to a `ResequenceEventListener.noOp()` bean when OTel is not available.

```java
@AutoConfiguration
@EnableConfigurationProperties(ResequenceProperties.class)
public class ResequenceAutoConfiguration {

    /**
     * Registers an OTel-backed listener when:
     * 1. opentelemetry-api is on the runtime classpath
     * 2. No user-provided ResequenceEventListener bean exists
     * 3. resequence.otel.enabled is not false
     */
    @Bean
    @ConditionalOnClass(name = "io.opentelemetry.api.OpenTelemetry")
    @ConditionalOnMissingBean(ResequenceEventListener.class)
    @ConditionalOnProperty(prefix = "resequence.otel", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ResequenceEventListener otelResequenceEventListener(OpenTelemetry openTelemetry) {
        return new OtelResequenceEventListener(openTelemetry);
    }

    /**
     * Fallback no-op listener when OTel is not configured or disabled.
     */
    @Bean
    @ConditionalOnMissingBean(ResequenceEventListener.class)
    public ResequenceEventListener noOpResequenceEventListener() {
        return ResequenceEventListener.noOp();
    }
}
```

**Key decisions**:
- `@ConditionalOnClass(name = "io.opentelemetry.api.OpenTelemetry")` uses the string form because the class is `compileOnly` — using the class literal directly would cause a `NoClassDefFoundError` if OTel is absent at runtime
- `@ConditionalOnMissingBean(ResequenceEventListener.class)` ensures user-provided beans always take precedence over auto-configured ones
- `@ConditionalOnProperty(matchIfMissing = true)` means OTel is enabled by default when the property is absent
- The `OpenTelemetry openTelemetry` parameter in `otelResequenceEventListener` — users must provide an `OpenTelemetry` bean (e.g., via `opentelemetry-spring-boot-starter`); the auto-config relies on it being present when OTel is on the classpath
- Add required imports: `OpenTelemetry` from `io.opentelemetry.api` — use `compileOnly` scope in auto-config, same as the listener

**Implementation steps**:

1. Add the two `@Bean` methods to `ResequenceAutoConfiguration`
2. Add imports for `ConditionalOnClass`, `ConditionalOnMissingBean`, `ConditionalOnProperty`, `OpenTelemetry`, `OtelResequenceEventListener`, `ResequenceEventListener`
3. Verify: `./gradlew :resequence-starter:compileJava`

---

### Sample-App Build Dependencies

**sample-app/build.gradle.kts** — add OTel SDK so the OTel auto-config bean can be resolved at runtime:

```kotlin
// OTel API + SDK — runtime dependency for the sample-app
implementation("io.opentelemetry:opentelemetry-api")
implementation("io.opentelemetry:opentelemetry-sdk")

// Provides a Spring Boot auto-configured OpenTelemetry bean (GlobalOpenTelemetry)
// Alternatively, manually create an OpenTelemetry bean in a @Configuration class
implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
```

**Note**: If `opentelemetry-spring-boot-starter` introduces too many transitive dependencies, the alternative is a simple `@Configuration` class that creates an `OpenTelemetrySdk` bean with an `InMemoryMetricReader` or `LoggingMetricExporter`. The spec prefers the starter for simplicity but the executing agent should evaluate what's available at implementation time and pick the lightest option.

**OTel version note**: Check if the Spring Boot 4.x BOM manages `io.opentelemetry` versions. If not, align with whatever version was used in the `resequence-starter` `compileOnly` dep.

---

### ResequenceTopologyConfig — Inject Listener

**Pattern to follow**: `sample-app/src/main/java/com/example/sampleapp/config/ResequenceTopologyConfig.java` (existing method parameter injection pattern)

**Changes**:
1. Add `ResequenceEventListener listener` as a parameter to `resequencingTopology(...)` — Spring injects the auto-configured bean
2. Update the `topology.addProcessor(...)` call to use the 6-arg `ResequenceProcessor` constructor:

```java
// Before
() -> new ResequenceProcessor<>(resequenceComparator, stateStoreName, resequenceProperties.getFlushInterval(), keyMapper, valueMapper)

// After
() -> new ResequenceProcessor<>(resequenceComparator, stateStoreName, resequenceProperties.getFlushInterval(), keyMapper, valueMapper, listener)
```

3. Add import: `import com.snekse.kafka.streams.resequence.listener.ResequenceEventListener;`

**Implementation steps**:
1. Add `ResequenceEventListener listener` parameter to `resequencingTopology` method signature
2. Update processor supplier lambda to pass `listener` as 6th arg
3. Add import
4. Verify: `./gradlew :sample-app:compileJava`

---

### MetricsShutdownLogger

**Pattern to follow**: Standard Spring `ApplicationListener<E>` pattern.

**Overview**: Logs a one-line metrics summary when the Spring application context closes. Injected with `OtelResequenceEventListener` by type to access the `AtomicLong` accumulators.

```java
package com.example.sampleapp.observability;

import com.snekse.kafka.streams.resequence.listener.OtelResequenceEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Component
public class MetricsShutdownLogger implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(MetricsShutdownLogger.class);

    private final OtelResequenceEventListener listener;

    public MetricsShutdownLogger(OtelResequenceEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Resequencer metrics summary — " +
                "buffered={} tombstones={} ignored={} forwarded={} flushes={}",
                listener.getTotalRecordsBuffered(),
                listener.getTotalTombstonesReceived(),
                listener.getTotalRecordsIgnored(),
                listener.getTotalRecordsForwarded(),
                listener.getTotalFlushes());
    }
}
```

**Key decisions**:
- Inject `OtelResequenceEventListener` directly (not the interface) to access the accumulator getters — acceptable in the sample-app since it's a demonstration, not a library
- Uses SLF4J `log.info` — visible in default Spring Boot console output without additional configuration
- `@Component` is sufficient; no need for `@Order` since this is a logging-only shutdown hook

**Implementation steps**:

1. Create `observability` package under `sample-app/src/main/java/com/example/sampleapp/`
2. Create `MetricsShutdownLogger.java`
3. Verify: `./gradlew :sample-app:compileJava`

## Testing Requirements

### Unit Tests

| Test File | Coverage |
| --- | --- |
| `resequence-starter/src/test/groovy/.../listener/OtelResequenceEventListenerSpec.groovy` | Counter values, histogram recordings, accumulator accessors, flush timing |

**Key test cases**:
- Send N non-null-value records → `resequence.records.buffered` counter == N; `resequence.tombstones.received` counter == 0
- Send 1 null-value (tombstone) record → `resequence.records.buffered` counter == 1 AND `resequence.tombstones.received` counter == 1 (both fire)
- Send 1 null-key record → `resequence.records.ignored` counter == 1; `resequence.records.buffered` counter == 0
- Advance clock past flush interval → `resequence.flushes` counter == 1 (with `full_scan=true`); `resequence.flush.duration` histogram has 1 data point > 0
- Advance clock a second time → second flush counter entry with `full_scan=false`
- Send 3 records under same key → `resequence.buffer.size` histogram has 1 data point with value 3 after flush
- Accumulator getters (`getTotalRecordsBuffered`, `getTotalTombstonesReceived`, `getTotalRecordsIgnored`, `getTotalRecordsForwarded`, `getTotalFlushes`) return values matching OTel counter readings
- `OtelResequenceEventListener` created with `GlobalOpenTelemetry.get()` (no-op OTel) does not throw — validates compileOnly safety

### Manual Testing

- [ ] `./gradlew :sample-app:bootRun` starts without errors
- [ ] Send some records to the source topic (or let the app idle)
- [ ] Stop the app (Ctrl+C or SIGTERM) and observe the console for a `Resequencer metrics summary` log line
- [ ] Verify the values look reasonable (buffered ≥ forwarded if app ran briefly, dropped == 0 for well-formed input)

## Error Handling

| Error Scenario | Handling Strategy |
| --- | --- |
| OTel API absent at runtime (user forgot SDK dep) | Auto-config no-op bean kicks in; processor works; OTel metrics silently absent |
| `OpenTelemetry` bean not provided but API is on classpath | Spring fails to create `otelResequenceEventListener` bean — document that users must provide an `OpenTelemetry` bean or use `opentelemetry-spring-boot-starter` |
| `MetricsShutdownLogger` constructed when `OtelResequenceEventListener` bean is absent | Spring injection fails at startup — `MetricsShutdownLogger` should use `Optional<OtelResequenceEventListener>` or check `@ConditionalOnBean(OtelResequenceEventListener.class)` |

**Note on the third error**: If `resequence.otel.enabled=false` or OTel is absent, the auto-config registers a `ResequenceEventListener` no-op bean — not an `OtelResequenceEventListener` bean. The `MetricsShutdownLogger` injects `OtelResequenceEventListener` by type, so it would fail. Fix: annotate `MetricsShutdownLogger` with `@ConditionalOnBean(OtelResequenceEventListener.class)`, or change the constructor param to `Optional<OtelResequenceEventListener>` with a null guard in `onApplicationEvent`.

## Validation Commands

```bash
# Verify compileOnly — OTel must NOT appear in the starter JAR
./gradlew :resequence-starter:jar
jar tf resequence-starter/build/libs/*.jar | grep opentelemetry  # should return nothing

# Run resequence-starter tests including new OTel spec
./gradlew :resequence-starter:test

# Build everything
./gradlew clean build

# Manual end-to-end: start sample app and observe shutdown log
./gradlew :sample-app:bootRun
```

---

_This spec is ready for implementation. Follow the patterns and validate at each step._
