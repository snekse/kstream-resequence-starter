# Implementation Spec: Resequencer Restructure - Phase 3

**Contract**: ./contract.md
**Estimated Effort**: M

## Technical Approach

Phase 3 updates the sample app to use the new module structure and builder API. This phase proves the restructure works end-to-end: a real Spring Boot application with embedded Kafka, demonstrating YAML configuration, domain-specific comparator, key/value mapping, and integration tests.

The approach is: update the sample app's dependency from `resequence-starter` to `resequence-spring-boot-starter`, rewrite `ResequenceTopologyConfig` to use the builder API (injecting the auto-configured builder, adding domain-specific comparator and serdes, calling `build()`), update all tests to work with the new APIs, and verify the full integration test suite passes with embedded Kafka.

The sample app also serves as validation that the library's developer experience matches the project vision: a user should define their comparator, configure a few properties, and wire one processor into their topology. If the sample app's topology config is more complex than that, the API needs revisiting.

## Feedback Strategy

**Inner-loop command**: `./gradlew :sample-app:test`

**Playground**: Integration test suite with embedded Kafka. The `OutOfOrderSpec` is the primary end-to-end validation — it produces shuffled records, waits for resequencing, and verifies correct output order.

**Why this approach**: The sample app's tests exercise the full stack: Spring Boot auto-config, topology wiring, embedded Kafka, producer/consumer, and the resequencer processor. Fast scoped tests catch wiring issues immediately.

## File Changes

### Modified Files

| File Path | Changes |
|-----------|---------|
| `sample-app/build.gradle.kts` | Change dependency from `:resequence-starter` to `:resequence-spring-boot-starter`. Remove `BufferedRecordListSerde`-related Jackson imports. |
| `sample-app/src/main/java/.../config/ResequenceTopologyConfig.java` | Rewrite to use builder API: inject `Resequencer.Builder`, add comparator/serdes, build, wire topology. Significant simplification expected. |
| `sample-app/src/main/java/.../domain/SampleRecordComparator.java` | Update `BufferedRecord` accessor calls from `getRecord()` → `record()`, `getPartition()` → `partition()`, etc. |
| `sample-app/src/main/java/.../domain/SampleRecord.java` | No changes expected unless accessor patterns change |
| `sample-app/src/test/groovy/.../OutOfOrderSpec.groovy` | Update any direct `BufferedRecord` construction or accessor usage |
| `sample-app/src/test/groovy/.../domain/SampleRecordComparatorSpec.groovy` | Update `BufferedRecord.builder()...build()` → `new BufferedRecord<>(...)` throughout |
| `sample-app/src/test/groovy/.../processor/ResequenceProcessorSpec.groovy` | Update serde construction, BufferedRecord construction, topology wiring to use builder API |
| `sample-app/src/test/groovy/.../TestKafkaStreamsConfig.groovy` | Likely minimal changes; verify compatibility |

### Deleted Files

| File Path | Reason |
|-----------|--------|
| (none expected) | Sample app files are updated, not deleted |

## Implementation Details

### 1. Build Configuration Update

**Overview**: Swap the dependency from the old module to the new starter.

```kotlin
// sample-app/build.gradle.kts — change:
implementation(project(":resequence-starter"))   // ← deleted in Phase 2
// to:
implementation(project(":resequence-spring-boot-starter"))
```

Note: `resequence-starter` was removed from `settings.gradle.kts` in Phase 2, so this dependency MUST be updated or the build won't resolve.

**Key decisions**:
- The sample app depends on the starter (not the core) because it's a Spring Boot application
- `spring-boot-starter-json` may still be needed for the app's own Jackson configuration (for Kafka value serialization of `SampleRecord` over the wire). This is the application's own dependency, not the library's.
- `BufferedRecordListSerde` no longer needs Jackson — it's a binary serde accepting `Serde<V>`. But the sample app still needs `JacksonJsonSerde` for its Kafka topic value serde (serializing `SampleRecord` as JSON over the wire). That's fine — that's the app's choice, not the library's.

**Implementation steps**:
1. Update `build.gradle.kts` dependency
2. Verify `./gradlew :sample-app:compileJava` passes
3. Fix any import errors from the module restructure

### 2. ResequenceTopologyConfig Rewrite

**Pattern to follow**: The builder API examples from Phase 1's spec

**Overview**: This is the key file demonstrating the library's developer experience. Rewrite it to use the auto-configured builder from the starter, add domain-specific configuration, and wire the topology.

```java
@Configuration
@EnableKafkaStreams
public class ResequenceTopologyConfig {

    @Bean
    public Serde<SampleRecord> sampleRecordSerde() {
        return new JacksonJsonSerde<>(SampleRecord.class);
    }

    @Bean
    public ResequenceComparator<SampleRecord> resequenceComparator(ResequenceProperties properties) {
        return new SampleRecordComparator(properties.getTombstoneSortOrder());
    }

    @Bean
    public Topology resequencingTopology(
            @Value("${app.pipeline.source.topic}") String sourceTopic,
            @Value("${app.pipeline.sink.topic}") String sinkTopic,
            Resequencer.Builder<?, ?> resequencerBuilder,  // auto-configured by starter
            Serde<SampleRecord> sampleRecordSerde,
            ResequenceComparator<SampleRecord> resequenceComparator,
            StreamsBuilder builder) {

        // Complete the builder with domain-specific pieces
        var resequencer = resequencerBuilder
            .comparator(resequenceComparator)
            .valueSerde(sampleRecordSerde)
            .keySerde(Serdes.Long())
            .keyMapper(key -> key + "-sorted")
            .valueMapper((outputKey, buffered) -> {
                SampleRecord record = buffered.record();
                if (record != null) {
                    record.setNewKey(outputKey);
                }
                return record;
            })
            .build();

        Topology topology = builder.build();

        // Add source
        topology.addSource("source",
                Serdes.Long().deserializer(),
                sampleRecordSerde.deserializer(),
                sourceTopic);

        // Add resequencer (processor + state store)
        // If addTo() convenience exists:
        resequencer.addTo(topology, "resequencer", "source");
        // Or manually:
        // topology.addProcessor("resequencer", resequencer.processorSupplier(), "source");
        // topology.addStateStore(resequencer.stateStoreBuilder(), "resequencer");

        // Add sink
        topology.addSink("sink",
                sinkTopic,
                Serdes.String().serializer(),
                sampleRecordSerde.serializer(),
                "resequencer");

        return topology;
    }
}
```

**Key decisions**:
- The auto-configured `Resequencer.Builder` is injected with properties already applied (flush interval, tombstone sort order, state store name from YAML)
- The application adds what only it knows: the comparator, the serdes, and any mappers
- This is noticeably simpler than the current implementation — no manual `BufferedRecordListSerde` creation, no manual state store builder wiring
- The `BufferedRecordListSerde` is now internal to the builder/definition — the sample app never sees it
- Note: the builder type parameters may need adjustment based on Phase 1's final API. The executing agent should adapt.
- If the builder's type system doesn't support injecting a wildcard `Builder<?, ?>` cleanly, the sample app can create its own builder instead of relying on auto-config for the partially-configured one. The properties can still be injected and applied manually. Use judgment — the goal is clean, readable code.

**Implementation steps**:
1. Rewrite `ResequenceTopologyConfig.java` using the builder API
2. Remove the `bufferedRecordListSerde()` bean — no longer needed
3. Update the comparator bean if accessor names changed
4. Verify `./gradlew :sample-app:compileJava` passes
5. Run integration tests

**Feedback loop**:
- **Playground**: Run `./gradlew :sample-app:test --tests '*OutOfOrderSpec*'` — this is the end-to-end proof
- **Experiment**: Verify that: (1) records produced out of order are consumed in correct order, (2) key mapping works (output keys have `-sorted` suffix), (3) value enrichment works (`newKey` is populated), (4) tombstones are positioned correctly
- **Check command**: `./gradlew :sample-app:test`

### 3. SampleRecordComparator Updates

**Pattern to follow**: Existing `SampleRecordComparator.java`

**Overview**: Update accessor calls from Lombok-style `getRecord()`/`getPartition()` to Java record-style `record()`/`partition()`.

**Implementation steps**:
1. Find and replace all `BufferedRecord` getter calls:
   - `getRecord()` → `record()`
   - `getPartition()` → `partition()`
   - `getOffset()` → `offset()`
   - `getTimestamp()` → `timestamp()`
2. Verify compilation

### 4. Test Updates

**Overview**: Update all sample-app test files for the new APIs. The main changes are `BufferedRecord` construction (record constructor instead of builder) and accessor names.

**Key changes per test file**:

**`SampleRecordComparatorSpec.groovy`** (~294 lines):
- Replace all `BufferedRecord.<SampleRecord>builder().record(x).partition(p).offset(o).timestamp(t).build()` with `new BufferedRecord<>(x, p, o, t)`
- This is a bulk find-replace across many test cases

**`ResequenceProcessorSpec.groovy`** (sample-app variant, ~351 lines):
- Replace `BufferedRecordListSerde<>(SampleRecord, objectMapper)` with `new BufferedRecordListSerde<>(sampleRecordSerde)`
- Replace topology construction with builder API if desired (or keep manual wiring — both are valid test approaches)
- Update BufferedRecord construction

**`OutOfOrderSpec.groovy`** (~302 lines):
- Likely minimal changes — this test uses KafkaTemplate/KafkaConsumer, not BufferedRecord directly
- Verify it still works with the new module dependency

**`TestKafkaStreamsConfig.groovy`**:
- Minimal changes expected — verify it compiles with new imports

**Implementation steps**:
1. Update `SampleRecordComparatorSpec.groovy` — bulk replace BufferedRecord construction
2. Update `ResequenceProcessorSpec.groovy` — serde and BufferedRecord changes
3. Run `./gradlew :sample-app:test` after each file to catch issues incrementally
4. Verify `OutOfOrderSpec.groovy` passes without changes (or with minimal fixes)

**Feedback loop**:
- **Playground**: Run individual test specs after updating each
- **Experiment**: Run comparator spec first (pure unit test, fastest), then processor spec (TopologyTestDriver), then OutOfOrderSpec (embedded Kafka, slowest)
- **Check command**: `./gradlew :sample-app:test`

## Testing Requirements

### Unit Tests

| Test File | Coverage |
|-----------|----------|
| `SampleRecordComparatorSpec.groovy` | All 3 comparison levels, tombstone handling |
| `ResequenceProcessorSpec.groovy` | Processor with SampleRecord types, key/value mapping |

### Integration Tests

| Test File | Coverage |
|-----------|----------|
| `OutOfOrderSpec.groovy` | Full end-to-end: produce → resequence → consume with embedded Kafka |

**Key scenarios**:
- Records produced out of order are consumed in correct order (CREATE → UPDATE → DELETE)
- Source topic order differs from sink topic order
- Complex multi-level comparison (operation type + timestamp + Kafka metadata)
- Null key handling (records skipped)
- Tombstone handling per configured sort order

## Validation Commands

```bash
# Sample app tests
./gradlew :sample-app:test

# Full project build (all modules)
./gradlew clean build

# Verify the sample app boots (smoke test)
./gradlew :sample-app:bootRun
# (Ctrl+C after it starts successfully)

# Verify runtime classpath of core module has no Spring/Jackson
./gradlew :resequence-core:dependencies --configuration runtimeClasspath
```

## Open Items

- [ ] The auto-configured `Resequencer.Builder<?, ?>` wildcard type may not work cleanly with Spring injection depending on how the builder's generics are implemented in Phase 1. If this is awkward, the sample app can create its own builder and inject only `ResequenceProperties`. The spec's topology config example shows both approaches.

---

_This spec is ready for implementation. Follow the patterns and validate at each step._
