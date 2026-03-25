# Context Map: Resequencer Restructure

**Phase**: 3
**Scout Confidence**: 82/100
**Verdict**: GO

## Prior Phase Summary

Phase 1 (confidence 89/100, GO) created `resequence-core` with zero Spring/Jackson deps, binary serde, builder API (`Resequencer.java`), and migrated core source files from `resequence-starter/`. The `resequence-starter/` module was slimmed to only Spring config files.

Phase 2 (confidence 85/100, GO) created `resequence-spring-boot-starter` module with auto-configuration. `ResequenceProperties` moved to `com.snekse.kafka.streams.resequence.spring` package. `ResequenceAutoConfiguration` exposes a `Resequencer.Builder<?, ?, ?, ?>` bean pre-configured with properties-driven defaults. The old `resequence-starter/` module was removed from `settings.gradle.kts`.

## Dimensions

| Dimension | Score | Notes |
|---|---|---|
| Scope clarity | 17/20 | All 8 files to modify are identified. Changes per file are well-specified. Minor ambiguity: spec's topology config example shows `Resequencer.Builder<?, ?>` (2 type params) but actual builder is `Builder<?, ?, ?, ?>` (4 type params). Builder injection with wildcards may need pragmatic adaptation. |
| Pattern familiarity | 18/20 | Builder API (`Resequencer.java`) is fully read and understood. `Definition.addTo()` convenience method exists. `BufferedRecord` is now a Java record (not Lombok). All patterns clear. |
| Dependency awareness | 17/20 | All imports from resequence library into sample-app are mapped. `ResequenceTopologyConfig.java:5` uses stale import path (`config.ResequenceProperties` should be `spring.ResequenceProperties`). `BufferedRecord.builder()` no longer exists (was Lombok, now Java record constructor). |
| Edge case coverage | 14/20 | Key edge cases identified: wildcard builder injection, BufferedRecord accessor migration (Java vs Groovy), `BufferedRecordListSerde` constructor change (now takes `Serde<V>` not `Class<V>, JsonMapper`). Less clear: whether Groovy property-style access on Java records works transparently for all cases. |
| Test strategy | 16/20 | Inner loop: `./gradlew :sample-app:test`. Three tiers: `SampleRecordComparatorSpec` (pure unit), `ResequenceProcessorSpec` (TopologyTestDriver), `OutOfOrderSpec` (embedded Kafka). Framework is Spock 2.4 + Groovy 5.x. |

## Key Patterns

- `resequence-core/src/main/java/com/snekse/kafka/streams/resequence/Resequencer.java` — Builder API with 4 type params `<K, V, KR, VR>`. `build()` returns `Definition` which has `processorSupplier()`, `stateStoreBuilder()`, and `addTo(topology, processorName, parentNames)` convenience method. Builder requires comparator, valueSerde, keySerde. Optional: keyMapper, valueMapper, flushInterval, stateStoreName.

- `resequence-core/src/main/java/com/snekse/kafka/streams/resequence/domain/BufferedRecord.java` — Now a Java record: `public record BufferedRecord<T>(T record, int partition, long offset, long timestamp) {}`. No builder, no Lombok. Accessors are `record()`, `partition()`, `offset()`, `timestamp()` (record-style, not getter-style).

- `resequence-core/src/main/java/com/snekse/kafka/streams/resequence/serde/BufferedRecordListSerde.java` — Constructor now takes `Serde<V>` (line 21: `public BufferedRecordListSerde(Serde<V> valueSerde)`). No longer takes `Class<V>` and `JsonMapper`. Binary serde using DataOutputStream/DataInputStream.

- `resequence-spring-boot-starter/src/main/java/com/snekse/kafka/streams/resequence/spring/ResequenceAutoConfiguration.java` — Exposes `Resequencer.Builder<?, ?, ?, ?>` bean with stateStoreName and flushInterval pre-applied from properties. `@ConditionalOnMissingBean` allows override.

- `resequence-spring-boot-starter/src/main/java/com/snekse/kafka/streams/resequence/spring/ResequenceProperties.java` — Package `com.snekse.kafka.streams.resequence.spring` (moved from old `config` package). Constructor-bound `@ConfigurationProperties` with `stateStoreName`, `flushInterval`, `tombstoneSortOrder`.

## Dependencies

- `ResequenceTopologyConfig.java:5` — imports `com.snekse.kafka.streams.resequence.config.ResequenceProperties` (STALE — must change to `spring.ResequenceProperties`)
- `ResequenceTopologyConfig.java:6` — imports `BufferedRecord` from `domain` package (still valid)
- `ResequenceTopologyConfig.java:8-11` — imports `KeyMapper`, `ResequenceProcessor`, `ValueMapper`, `BufferedRecordListSerde` (some will be removed when switching to builder API)
- `SampleRecordComparator.java:3-5` — imports `BufferedRecord`, `ResequenceComparator`, `TombstoneSortOrder` from `domain` package (still valid, no path changes needed)
- `SampleRecordComparatorSpec.groovy:3-4` — imports `BufferedRecord`, `TombstoneSortOrder` (still valid paths)
- `ResequenceProcessorSpec.groovy:3-7,11` — imports `BufferedRecord`, `TombstoneSortOrder`, `KeyMapper`, `ResequenceProcessor`, `BufferedRecordListSerde`, `ValueMapper` (paths still valid, but `BufferedRecordListSerde` constructor usage must change)
- `TestKafkaStreamsConfig.groovy` — no direct resequence library imports; depends on `SampleRecord` and `StreamsStateDirFactory` (no changes needed)
- `OutOfOrderSpec.groovy` — no direct resequence library imports; uses `SampleRecord`, `SampleProducer`, Spring test infrastructure (minimal or no changes)

## Conventions

- **Naming**: Test files named `*Spec.groovy`. Java source follows standard package conventions.
- **Imports**: Individual type imports, no wildcards. Groovy specs use unqualified imports.
- **Error handling**: Builder validates required fields at `build()` time with `IllegalStateException`.
- **Types**: `BufferedRecord` is a Java record. `ResequenceComparator` is a `@FunctionalInterface` extending `Comparator`. Domain types use Lombok (`@Data`, `@Builder`).
- **Testing**: Spock 2.4 + Groovy 5.x. Three tiers: unit specs, TopologyTestDriver specs, embedded Kafka integration specs. `@AutoCleanup` for driver. `@SpringBootTest` + `@EmbeddedKafka` + `@ActiveProfiles('test')` for integration.
- **Build**: Gradle Kotlin DSL. `-parameters` compiler flag in starter module for constructor binding.
- **Groovy property access**: Groovy specs already use property-style access (`buffered.record`, `records[0].offset`, `records[0].partition`, `records[0].timestamp`) which works on Java records because Groovy resolves property access to the record's accessor methods.

## Risks

- **`BufferedRecord.builder()` no longer exists**: `SampleRecordComparatorSpec.groovy` uses `BufferedRecord.builder().record(x).partition(p).offset(o).timestamp(t).build()` in ~10 places. All must change to `new BufferedRecord<>(x, p, o, t)`.

- **`BufferedRecord` getter-style accessors removed**: `SampleRecordComparator.java` uses `getRecord()`, `getPartition()`, `getOffset()`, `getTimestamp()`. Must change to `record()`, `partition()`, `offset()`, `timestamp()`. `ResequenceTopologyConfig.java` also uses `buffered.getRecord()`.

- **Stale import path for `ResequenceProperties`**: `ResequenceTopologyConfig.java:5` imports from `com.snekse.kafka.streams.resequence.config.ResequenceProperties`. The class moved to `com.snekse.kafka.streams.resequence.spring.ResequenceProperties` in Phase 2.

- **`BufferedRecordListSerde` constructor change**: `ResequenceProcessorSpec.groovy` uses `new BufferedRecordListSerde<>(SampleRecord, JsonMapper.builder().build())`. The new constructor takes `Serde<V>`.

- **Wildcard builder injection**: Auto-config exposes `Resequencer.Builder<?, ?, ?, ?>`. Spring may not inject cleanly due to wildcards. Builder's `keyMapper()` and `valueMapper()` methods change type parameters. May need to create a fresh builder in the topology config.

- **Groovy property access on Java records**: Should work — Groovy resolves `obj.record` to `obj.record()`.
