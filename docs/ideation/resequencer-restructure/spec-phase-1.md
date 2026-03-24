# Implementation Spec: Resequencer Restructure - Phase 1

**Contract**: ./contract.md
**Estimated Effort**: L

## Technical Approach

Phase 1 creates the `resequence-core` module — the zero-dependency foundation that all other modules build on. This is the most critical phase: it establishes the published artifact that any Kafka Streams application can adopt.

The approach is to create `resequence-core/` as a new module and `git mv` the core source files there from `resequence-starter/`. The `resequence-starter/` directory is NOT deleted in this phase — it remains as a thin shell containing only the Spring config classes (`ResequenceProperties`, `ResequenceAutoConfiguration`) with its `build.gradle.kts` updated to depend on `:resequence-core`. This preserves git history for moved files and keeps the old module available until Phase 2 moves the remaining Spring files to `resequence-spring-boot-starter/`.

The moved core files are then modified: `BufferedRecord` becomes a Java record, the Jackson-based serde is replaced with a composite binary serde, and a builder API is added. Existing tests are `git mv`'d to the new module and adapted to work without Spring Kafka's `JacksonJsonSerde` by using a small Jackson-based test helper (Jackson as test-only dependency). All existing test behaviors must continue to pass.

**Important**: The sample app (`:sample-app`) depends on `:resequence-starter` which will transitively get `:resequence-core`. However, the `BufferedRecord` accessor rename (`getRecord()` → `record()`) will break the sample app's compilation. This is expected — the sample app will be fixed in Phase 3. During Phase 1, validate using `:resequence-core:test` and `:resequence-starter:compileJava` only.

## Feedback Strategy

**Inner-loop command**: `./gradlew :resequence-core:test`

**Playground**: Test suite — TopologyTestDriver-based Spock tests are the primary validation mechanism. They run in seconds without needing a real Kafka cluster.

**Why this approach**: All changes are to data-layer and processor logic. The TopologyTestDriver provides fast, deterministic feedback on buffering, sorting, serde round-trips, and builder validation.

## File Changes

### New Files

| File Path | Purpose |
|-----------|---------|
| `resequence-core/build.gradle.kts` | Build config with only `kafka-streams` as API dependency |
| `resequence-core/src/main/java/.../Resequencer.java` | Builder entry point and definition holder — the main user-facing API |

### Modified Files

(These files are `git mv`'d from `resequence-starter/src/` to `resequence-core/src/` and then modified)

| File Path | Changes |
|-----------|---------|
| `resequence-core/src/main/java/.../domain/BufferedRecord.java` | `git mv` from starter, then convert from Lombok `@Data`/`@Builder` to Java `record` |
| `resequence-core/src/main/java/.../serde/BufferedRecordListSerde.java` | `git mv` from starter, then rewrite: accept `Serde<V>`, binary format |
| `resequence-core/src/main/java/.../processor/ResequenceProcessor.java` | `git mv` from starter, then simplify constructors for builder integration |
| `resequence-core/src/main/java/.../processor/KeyMapper.java` | `git mv` from starter, no changes |
| `resequence-core/src/main/java/.../processor/ValueMapper.java` | `git mv` from starter, no changes |
| `resequence-core/src/main/java/.../domain/ResequenceComparator.java` | `git mv` from starter, no changes |
| `resequence-core/src/main/java/.../domain/TombstoneSortOrder.java` | `git mv` from starter, no changes |
| `resequence-core/src/test/groovy/.../processor/ResequenceProcessorSpec.groovy` | `git mv` from starter, then replace `JacksonJsonSerde` with test-only serde, update `BufferedRecord` construction |
| `resequence-core/src/test/groovy/.../serde/BufferedRecordListSerdeSpec.groovy` | `git mv` from starter, then rewrite for binary serde API |
| `resequence-core/src/test/groovy/.../domain/TombstoneSortOrderSpec.groovy` | `git mv` from starter, no logic changes |
| `settings.gradle.kts` | Add `resequence-core` (keep `resequence-starter` for now) |
| `resequence-starter/build.gradle.kts` | Slim down: depend on `:resequence-core`, keep only Spring dependencies for the remaining config classes |

### Files Remaining in `resequence-starter/` (NOT deleted — moved in Phase 2)

| File Path | Why it stays |
|-----------|-------------|
| `resequence-starter/src/main/java/.../config/ResequenceProperties.java` | Will be `git mv`'d to `resequence-spring-boot-starter` in Phase 2 |
| `resequence-starter/src/main/java/.../config/ResequenceAutoConfiguration.java` | Will be `git mv`'d to `resequence-spring-boot-starter` in Phase 2 |
| `resequence-starter/src/main/resources/META-INF/spring/...imports` | Will be `git mv`'d in Phase 2 |
| `resequence-starter/build.gradle.kts` | Updated to depend on `:resequence-core`; removed in Phase 2 |

## Implementation Details

### 1. Build Configuration

**Overview**: The core module depends only on `kafka-streams`. Lombok is removed since `BufferedRecord` becomes a Java record.

```kotlin
// resequence-core/build.gradle.kts
plugins {
    `java-library`
    groovy
}

dependencies {
    api("org.apache.kafka:kafka-streams")

    // Test only — Jackson for test value serde, Spock for BDD tests
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("org.spockframework:spock-core:2.4-groovy-5.0")
    testImplementation("org.apache.kafka:kafka-streams-test-utils")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

**Key decisions**:
- `kafka-streams` is `api` scope because consumers need Processor API types (`ContextualProcessor`, `Record`, `KeyValueStore`, etc.)
- Lombok removed entirely — only `BufferedRecord` used it, and it becomes a Java record
- Jackson is test-only (for a convenient test value serde)
- The root `build.gradle.kts` applies `io.spring.dependency-management` + Spring BOM to all subprojects for version management. This is build-time only and doesn't add runtime dependencies. If this feels wrong for the core module, an alternative is to extract version management into a Gradle version catalog (`libs.versions.toml`). Either approach is acceptable — prioritize what's simplest for the project.

**Implementation steps**:
1. Create `resequence-core/` directory structure
2. `git mv` core source files (processor/, domain/, serde/) from `resequence-starter/src/main/` to `resequence-core/src/main/`
3. `git mv` test files from `resequence-starter/src/test/` to `resequence-core/src/test/`
4. Create `resequence-core/build.gradle.kts` per above
5. Update `settings.gradle.kts` to add `resequence-core` (keep `resequence-starter` — it still has Spring config classes)
6. Update `resequence-starter/build.gradle.kts`: slim down to `api(project(":resequence-core"))` + `implementation("org.springframework.boot:spring-boot-autoconfigure")` — just enough for the remaining config classes to compile
7. Verify `./gradlew :resequence-core:compileJava` passes with only `kafka-streams` on classpath
8. Verify `./gradlew :resequence-starter:compileJava` passes (the thin shell still compiles)

### 2. BufferedRecord as Java Record

**Pattern to follow**: Standard Java 21 record pattern

**Overview**: Convert from Lombok-annotated class to a Java record. This eliminates the Lombok build dependency and is more idiomatic for an immutable data carrier.

```java
// Before (Lombok)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BufferedRecord<T> {
    private T record;
    private int partition;
    private long offset;
    private long timestamp;
}

// After (Java record)
public record BufferedRecord<T>(T record, int partition, long offset, long timestamp) {}
```

**Key decisions**:
- Java records provide `equals()`, `hashCode()`, `toString()`, and accessor methods automatically
- Accessor methods use the field name directly (`record()`, `partition()`, etc.) instead of `getRecord()`, `getPartition()` — this is a breaking API change but acceptable since the library isn't published yet
- No builder pattern — construction via `new BufferedRecord<>(value, partition, offset, timestamp)` is clear enough for a 4-field type
- Compact canonical constructor can be added later for validation if needed

**Implementation steps**:
1. Replace `BufferedRecord.java` with a Java record
2. Update all references from `getRecord()` → `record()`, `getPartition()` → `partition()`, etc.
3. Update all references from `BufferedRecord.builder()...build()` → `new BufferedRecord<>(...)`
4. Remove Lombok plugin from `build.gradle.kts` if no other classes use it (verify none do)

**Feedback loop**:
- **Playground**: Run `./gradlew :resequence-core:compileJava` after conversion to catch accessor name changes
- **Experiment**: Verify compilation succeeds, then run `./gradlew :resequence-core:test` to confirm all accessor changes are consistent
- **Check command**: `./gradlew :resequence-core:test`

### 3. Composite Binary BufferedRecordListSerde

**Pattern to follow**: Standard `java.io.DataOutputStream` / `DataInputStream` pattern for binary serialization

**Overview**: Replace Jackson-based serialization with a binary format that delegates value serialization to the user's existing `Serde<V>`. The binary format serializes `BufferedRecord` metadata (primitives) directly and delegates only the value `V` to the user's serde.

```java
public class BufferedRecordListSerde<V> implements Serde<List<BufferedRecord<V>>> {

    private final Serde<V> valueSerde;

    public BufferedRecordListSerde(Serde<V> valueSerde) {
        this.valueSerde = valueSerde;
    }

    // serializer() and deserializer() implement binary format
}
```

**Binary format**:
```
List:    [4 bytes: count (int)]  [record_0] [record_1] ...
Record:  [4 bytes: partition (int)]
         [8 bytes: offset (long)]
         [8 bytes: timestamp (long)]
         [4 bytes: value_length (int), -1 means null/tombstone]
         [value_length bytes: serialized V via user's serde]
```

**Key decisions**:
- `DataOutputStream`/`DataInputStream` for endian-consistent binary I/O (Java standard library, no extra deps)
- `value_length = -1` signals a null value (tombstone) — no value bytes follow
- `null` list input → `null` bytes output (Kafka convention for tombstones at the store level)
- `null` bytes input → empty `ArrayList` (safe default, matches existing behavior)
- Errors wrap in `SerializationException` with context (matches Kafka conventions and existing behavior)
- The user's `Serde<V>` is used for the value portion only — the metadata fields are always binary regardless of the user's serialization format

**Implementation steps**:
1. Replace the constructor: `(Class<T>, ObjectMapper)` → `(Serde<V>)`
2. Implement `serializer()`: write count, then for each record write metadata + value bytes
3. Implement `deserializer()`: read count, then for each record read metadata + value bytes
4. Handle null list/bytes per existing contract
5. Wrap IO exceptions in `SerializationException`

**Feedback loop**:
- **Playground**: Create/update `BufferedRecordListSerdeSpec.groovy` with a describe block and smoke test before rewriting the serde
- **Experiment**: Test round-trip with: empty list, single record, multiple records, null values (tombstones), null list input, null bytes input, corrupted bytes (should throw SerializationException)
- **Check command**: `./gradlew :resequence-core:test --tests '*BufferedRecordListSerdeSpec*'`

### 4. Builder API (Resequencer class)

**Overview**: A new `Resequencer` class serves as the main entry point for the library. It provides a fluent builder that produces a `ResequenceDefinition` — a bundle containing the processor supplier and state store builder, ready to wire into any topology.

```java
// Minimal usage (no key/value mapping):
var resequencer = Resequencer.<Long, MyRecord>builder()
    .comparator(myComparator)
    .valueSerde(myRecordSerde)
    .keySerde(Serdes.Long())
    .build();

// Wire into topology:
topology.addProcessor("resequencer", resequencer.processorSupplier(), "source");
topology.addStateStore(resequencer.stateStoreBuilder(), "resequencer");

// With key mapping:
var resequencer = Resequencer.<Long, MyRecord>builder()
    .comparator(myComparator)
    .valueSerde(myRecordSerde)
    .keySerde(Serdes.Long())
    .keyMapper(key -> key + "-sorted")
    .build();

// With both key and value mapping:
var resequencer = Resequencer.<Long, MyRecord>builder()
    .comparator(myComparator)
    .valueSerde(myRecordSerde)
    .keySerde(Serdes.Long())
    .keyMapper(key -> key + "-sorted")
    .valueMapper((outputKey, buffered) -> buffered.record())
    .build();
```

**Key decisions**:
- `Resequencer` is the entry point (not `ResequenceProcessor.builder()`) — cleaner API surface, and the returned object is a definition, not a processor
- Builder validates at `build()` time: comparator, valueSerde, and keySerde are required; missing any throws `IllegalStateException` with a clear message
- Sensible defaults: `flushInterval = Duration.ofSeconds(2)`, `tombstoneSortOrder = TombstoneSortOrder.LAST`, `stateStoreName = "resequence-buffer"`
- The builder should handle the type safety concern from #21: if keyMapper is not provided, the processor uses identity mapping where `K == KR`. The unchecked cast `(KR) key` in the current code should be replaced with an explicit identity `KeyMapper` created by the builder. This avoids the `ClassCastException` risk entirely.
- The `ResequenceDefinition` (or whatever the built object is named) should expose:
  - `processorSupplier()` — for `topology.addProcessor()`
  - `stateStoreBuilder()` — for `topology.addStateStore()`
  - `stateStoreName()` — for `topology.connectProcessorAndStateStores()` if needed
- Consider also providing a convenience method like `addTo(Topology topology, String processorName, String... parentNames)` that does the addProcessor + addStateStore + connectProcessorAndStateStores in one call. This reduces the wiring boilerplate for the common case.

**Implementation steps**:
1. Create `Resequencer.java` with static `builder()` method
2. Implement the builder with required/optional field tracking
3. Implement `build()` with validation (required fields, type safety checks)
4. Create `ResequenceDefinition` (or inner class) that bundles processorSupplier + stateStoreBuilder
5. Wire the builder into `ResequenceProcessor` construction — the processor's constructors can become package-private since users go through the builder
6. Add convenience `addTo(Topology, ...)` method on the definition

**Feedback loop**:
- **Playground**: Create a new test spec (e.g., `ResequencerBuilderSpec.groovy`) with tests for builder validation before implementing
- **Experiment**: Test builder with: all defaults, custom config, missing required fields (should throw), keyMapper provided, keyMapper not provided (identity), valueMapper provided. Then run the full processor spec to verify the builder integrates correctly.
- **Check command**: `./gradlew :resequence-core:test`

### 5. ResequenceProcessor Updates

**Pattern to follow**: Existing `ResequenceProcessor.java`

**Overview**: Simplify the processor's construction now that the builder handles configuration. The processor's existing logic (buffering, dirty-key tracking, flush paths, forwarding) stays the same. The main changes are: constructors become package-private (builder is the public API), the unchecked `(KR) key` cast is replaced by an explicit identity `KeyMapper`, and accessor method calls update for the `BufferedRecord` record type.

**Key decisions**:
- Processor logic (process, flushAll, flushViaFullScan, flushViaDirtyKeys, flushKey) is unchanged — it's well-tested and correct
- The identity `KeyMapper` replaces the null check + unchecked cast, fixing #21
- `BufferedRecord.builder()` calls in `process()` become `new BufferedRecord<>(...)` constructor calls
- `buffered.getRecord()` becomes `buffered.record()`, etc.

**Implementation steps**:
1. Update `process()` method: `BufferedRecord.builder()...build()` → `new BufferedRecord<>(...)`
2. Update `flushKey()`: replace `(KR) key` cast with `keyMapper.map(key)` (builder ensures keyMapper is never null — identity if not provided)
3. Update accessor calls if BufferedRecord accessor names change (record vs Java record style)
4. Make constructors package-private; public construction is via `Resequencer.builder()`
5. Verify all existing tests pass

### 6. Test Migration

**Overview**: Move all test files from `resequence-starter/src/test/` to `resequence-core/src/test/` and adapt them to work without Spring Kafka dependencies.

**Key changes**:
- Replace `JacksonJsonSerde<>(TestRecord)` with a test-only serde. Options:
  - A small `TestJsonSerde<T>` class in test sources that uses Jackson's `ObjectMapper` directly (Jackson is a test-only dependency)
  - Or use a string-based approach with Serdes.String() and string parsing for test records
  - The Jackson test serde approach is recommended — it's the most readable and closest to the existing tests
- Replace `BufferedRecordListSerde<>(TestRecord, objectMapper)` with `new BufferedRecordListSerde<>(testValueSerde)` using the new binary serde
- Replace `BufferedRecord.builder().record(x).build()` with `new BufferedRecord<>(x, 0, 0L, 0L)` (or appropriate metadata values)
- Remove all Spring imports (`spring-kafka`, `spring-boot-test`, etc.)

**Implementation steps**:
1. Create a test-only `TestJsonSerde<T>` helper using Jackson ObjectMapper
2. Update `ResequenceProcessorSpec.groovy`: replace serde references, update BufferedRecord construction
3. Update `BufferedRecordListSerdeSpec.groovy`: rewrite for binary serde API
4. Update `TombstoneSortOrderSpec.groovy`: move file, no logic changes expected
5. Run full test suite and verify all tests pass

**Feedback loop**:
- **Playground**: After each test file migration, run that specific test
- **Experiment**: Run each spec individually to catch migration issues early, then run full suite
- **Check command**: `./gradlew :resequence-core:test`

## Testing Requirements

### Unit Tests

| Test File | Coverage |
|-----------|----------|
| `ResequenceProcessorSpec.groovy` | Buffering, flushing, sorting, key mapping, value mapping, null keys, tombstones, dirty-key optimization |
| `BufferedRecordListSerdeSpec.groovy` | Binary serde round-trip, null handling, tombstone serialization, error cases |
| `TombstoneSortOrderSpec.groovy` | Enum values and signum correctness |
| `ResequencerBuilderSpec.groovy` (new) | Builder validation, defaults, required field enforcement, identity KeyMapper |

**Key test cases**:
- Builder with all defaults produces working processor
- Builder missing required fields throws clear error
- Builder without keyMapper creates identity mapping (K == KR)
- Binary serde round-trips records with various value types
- Binary serde handles null values (tombstones) correctly
- Binary serde handles null list → null bytes, null bytes → empty list
- Binary serde throws SerializationException on corrupted data
- All existing processor behaviors preserved (9 existing tests)

## Error Handling

| Error Scenario | Handling Strategy |
|---|---|
| Missing required builder field (comparator, valueSerde, keySerde) | `IllegalStateException` with message naming the missing field |
| Serde serialization failure | `SerializationException` wrapping the cause, including topic name for context |
| Serde deserialization failure (corrupted state store data) | `SerializationException` wrapping the cause — Kafka Streams will handle retry/failure |
| Null key in process() | Silently skip (existing behavior, logged at TRACE) |

## Validation Commands

```bash
# Compile core — verifies no Spring/Jackson in production classpath
./gradlew :resequence-core:compileJava

# Compile the thin starter shell — verifies it can still reference core types
./gradlew :resequence-starter:compileJava

# Unit tests for core
./gradlew :resequence-core:test

# Verify core dependencies — should show only kafka-streams (and its transitives)
./gradlew :resequence-core:dependencies --configuration runtimeClasspath

# Note: ./gradlew build will FAIL because sample-app has breaking changes
# from BufferedRecord accessor renames. This is expected — fixed in Phase 3.
# Validate only core + starter modules in this phase.
```

## Open Items

- [ ] Exact naming: `Resequencer` vs `ResequenceBuilder` vs keeping construction on `ResequenceProcessor`. The spec recommends `Resequencer` as the entry point but the executing agent should use judgment.
- [ ] Whether the convenience `addTo(Topology, ...)` method belongs on the definition object or as a static utility. Both work; pick whichever reads better.
- [ ] The root `build.gradle.kts` applies Spring dependency management to all subprojects. This is fine for version management (build-time only) but could alternatively be moved to a Gradle version catalog. Not blocking — either approach works.

---

_This spec is ready for implementation. Follow the patterns and validate at each step._
