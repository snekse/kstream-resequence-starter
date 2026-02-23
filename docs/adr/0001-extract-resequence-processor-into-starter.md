# ADR 0001: Starter Module Scope and Dependencies

## Status

Accepted

## Context

The `resequence-starter` module is a reusable Spring Boot Starter library for the Resequence Enterprise Integration Pattern on Kafka Streams. Several design decisions need to be made about what belongs in the starter versus what belongs in consuming applications, and what the starter's dependency footprint should be.

Key concerns:
- The resequencing processor is pure Kafka Streams — it should not force a Spring Kafka dependency on consumers.
- The starter should not dictate topology structure (source/sink topics, key types) — applications have different wiring needs.
- Key mapping (`KeyMapper<K, KR>`) must remain generic to support arbitrary key type transformations.

## Decision

### What the starter provides

The starter lives under `com.snekse.kafka.streams.resequence` and contains only generic, reusable components:

| Component | Subpackage | Purpose                                                                   |
|---|---|---------------------------------------------------------------------------|
| `ResequenceProcessor` | `processor` | Kafka Streams processor that buffers, sorts, and forwards records         |
| `KeyMapper` | `processor` | Functional interface for key re-mapping                                   |
| `BufferedRecord` | `domain` | Wraps records with Kafka metadata for ordering                            |
| `TombstoneSortOrder` | `domain` | Enum controlling tombstone sort position                                  |
| `BufferedRecordListSerde` | `serde` | Jackson-based serde for the state store; TODO: This needs to be revisted. |
| `ResequenceProperties` | `config` | Spring Boot configuration properties                                      |
| `ResequenceAutoConfiguration` | `config` | Auto-configuration activating properties                                  |

### What consuming applications provide

- A `Comparator<BufferedRecord<V>>` implementing domain-specific ordering logic
- Topology wiring (source topic, sink topic, state store, serdes)
- Domain record types

### Dependency choices

- `api("org.apache.kafka:kafka-streams")` — exposed as API since consumers need its types (e.g., `ContextualProcessor`, `KeyValueStore`)
- `implementation("org.springframework.boot:spring-boot-autoconfigure")` — for `@ConfigurationProperties` binding
- `implementation("tools.jackson.core:jackson-databind")` — for `BufferedRecordListSerde`

No `spring-kafka` dependency in production scope. The processor is pure Kafka Streams.

### No Topology bean

The starter does not create a `Topology` or `StreamsBuilder` bean. Applications wire their own topology, choosing their own source/sink topics, key serdes, and processor configuration.

## Consequences

- **Minimal dependency footprint**: kafka-streams + spring-boot-autoconfigure + jackson. Applications that don't use Spring Kafka are not forced to pull it in.
- **Applications have full control** over topology structure, key types, comparator logic, and enrichment callbacks.
- **Auto-configuration** is limited to binding `ResequenceProperties` from `resequence.*` properties — no opinionated wiring.
- **Trade-off**: more boilerplate in consuming applications to wire the topology. The `sample-app` module serves as a reference for this wiring.
