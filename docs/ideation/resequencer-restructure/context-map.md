# Context Map: Resequencer Restructure

**Phase**: 1
**Scout Confidence**: 89/100
**Verdict**: GO

## Dimensions

| Dimension | Score | Notes |
|---|---|---|
| Scope clarity | 19/20 | All files enumerated with specific changes. Only ambiguity is naming: `Resequencer` vs `ResequenceBuilder` (noted as Open Item in spec). |
| Pattern familiarity | 18/20 | All source files read. Standard Java 21 record pattern, DataOutputStream binary I/O, Kafka Streams Processor API -- all well-understood patterns. |
| Dependency awareness | 18/20 | Full blast radius mapped. Sample-app will break (expected, deferred to Phase 3). `ValueMapper.noOp()` at line 40 calls `getRecord()` and must be updated. `ResequenceAutoConfiguration` and `ResequenceProperties` stay in `resequence-starter` and only import from `config` package -- unaffected by core moves. |
| Edge case coverage | 16/20 | Null keys, null values (tombstones), null list serialization, null bytes deserialization, corrupted bytes, empty list, missing builder fields all identified. New binary serde introduces byte-level edge cases (value_length=-1 for tombstones). |
| Test strategy | 18/20 | Inner loop: `./gradlew :resequence-core:test`. 3 existing Spock specs migrate, 1 new builder spec. `JacksonJsonSerde` replaced with test-only Jackson serde. TopologyTestDriver provides fast deterministic feedback. |

## Key Patterns

- `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/domain/BufferedRecord.java` -- Current Lombok-annotated class. Will become a Java record. Uses `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`. Accessors are `getRecord()`, `getPartition()`, `getOffset()`, `getTimestamp()`. After conversion: `record()`, `partition()`, `offset()`, `timestamp()`.

- `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/serde/BufferedRecordListSerde.java` -- Current Jackson-based serde. Constructor takes `(Class<T>, ObjectMapper)`. Will be rewritten to take `(Serde<V>)` and use binary format with `DataOutputStream`/`DataInputStream`.

- `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/processor/ResequenceProcessor.java` -- Core processor. Two constructors: 3-arg and 5-arg. `process()` uses `BufferedRecord.builder()`. `flushKey()` has unchecked `(KR) key` cast -- this is the #21 bug to fix. Both constructors currently public, will become package-private.

- `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/processor/ValueMapper.java` -- Functional interface. The `noOp()` static method calls `bufferedRecord.getRecord()` -- must update to `bufferedRecord.record()`.

- `resequence-starter/build.gradle.kts` -- Current build config. Uses Lombok plugin, has Spring and Jackson deps. After Phase 1: slim down to `api(project(":resequence-core"))` + Spring autoconfigure deps only.

- `build.gradle.kts` -- Root build. Applies `io.spring.dependency-management` and Spring BOM to all `subprojects`. The new `resequence-core` module will inherit this.

- `settings.gradle.kts` -- Currently includes `resequence-starter` and `sample-app`. Must add `resequence-core`.

## Dependencies

- `BufferedRecord.java` -- consumed by:
  - `ResequenceProcessor.java` (builder construction in `process()`)
  - `ValueMapper.java` (`noOp()` calls `getRecord()`)
  - `BufferedRecordListSerde.java` (serialization/deserialization)
  - `ResequenceComparator.java` (generic type parameter)
  - `ResequenceProcessorSpec.groovy` (test assertions and construction)
  - `BufferedRecordListSerdeSpec.groovy` (test construction via builder)
  - `sample-app/SampleRecordComparator.java` (uses `getRecord()`, `getPartition()`, `getOffset()`, `getTimestamp()`) -- **Phase 3 fix**
  - `sample-app/ResequenceTopologyConfig.java` (uses `getRecord()`) -- **Phase 3 fix**

- `BufferedRecordListSerde.java` -- consumed by:
  - `ResequenceProcessorSpec.groovy` (constructed with `TestRecord, JsonMapper`)
  - `sample-app/ResequenceTopologyConfig.java` -- **Phase 3 fix**

- `ResequenceProcessor.java` -- consumed by:
  - `ResequenceProcessorSpec.groovy` (constructed via 5-arg constructor)
  - `sample-app/ResequenceTopologyConfig.java` -- **Phase 3 fix**

## Conventions

- **Naming**: Java files in `com.snekse.kafka.streams.resequence` package hierarchy. Sub-packages: `domain`, `processor`, `serde`, `config`. Test files named `*Spec.groovy`.
- **Imports**: Standard Java imports, no wildcard imports. Kafka types imported individually.
- **Error handling**: `SerializationException` wrapping original exceptions with topic-context messages.
- **Types**: Functional interfaces annotated with `@FunctionalInterface`. Generics with `<K, V, KR, VR>`.
- **Testing**: Spock 2.4 with Groovy 5.x. `TopologyTestDriver`. `@AutoCleanup`. Groovy closure coercion.
- **Build**: Gradle Kotlin DSL. Root applies Spring BOM to all subprojects. `java-library` plugin for library modules.

## Risks

- **Groovy property access for Java record accessors**: Groovy accesses record methods (`record()`, `partition()`) same way as JavaBean getters -- via property syntax (`.record`, `.partition`). Verify early with `compileGroovy`.
- **`ValueMapper.noOp()` calls `getRecord()`**: Must update to `bufferedRecord.record()` when `BufferedRecord` becomes a record.
- **Binary serde backward incompatibility**: New format incompatible with old Jackson JSON format. Acceptable since library isn't published.
- **Sample-app compilation will break**: Validate only `:resequence-core:test` and `:resequence-starter:compileJava` -- never `./gradlew build`.
- **Root build Spring BOM on all subprojects**: Build-time only, no runtime deps added. Should work fine.
- **Lombok removal**: Only `BufferedRecord.java` uses it. Safe to remove from `resequence-starter` after move.
