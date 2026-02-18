# Kafka Streams Resequence Starter

This project provides a Spring Boot Starter library to implement the **Resequence Enterprise Integration Pattern** using Kafka Streams.

## Goal

The simpler goal is to resequence messages based on a sequence number or timestamp without the overhead of full Spring Integration, specifically tailored for Kafka Streams environments.

See [Resequencer Pattern](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html).

---

Beyond the core starter, the project includes two reference implementations comparing **Kafka Streams** vs. **Spring Integration** approaches — demonstrating that the same resequencing result can be achieved with either framework.

## The Problem

A Kafka topic receives events for the same entity (e.g., clientId `1001`) but they arrive out of order:

```
Source topic (as received):      DELETE(t+2s) → UPDATE(t+1s) → CREATE(t)
Sink topic (after resequencing): CREATE(t)    → UPDATE(t+1s) → DELETE(t+2s)
```

The downstream consumer needs them in logical order: `CREATE → UPDATE → DELETE`. This isn't simple timestamp sorting — the correct order is derived from **business logic**: operation type takes priority over timestamps. Messages for the same key may arrive across multiple partitions, at different offsets, with different Kafka timestamps.

The resequencer must handle: null keys (skip), tombstones/null values (configurable position), and ties at every comparison level.

## How Resequencing Works

### Ordering Logic

Both implementations share a multi-level comparator that determines the correct message order:

1. **Operation type** — `CREATE (0) < UPDATE (1) < DELETE (2)`. A `CREATE` always comes before an `UPDATE` regardless of timestamps.
2. **Payload timestamp** — Within the same operation type, earlier timestamps come first.
3. **Kafka metadata tiebreaker** — When operation type and payload timestamp are identical:
   - **Same partition:** lower offset wins (preserves original produce order)
   - **Different partitions:** lower Kafka timestamp wins (best available cross-partition ordering)

### Tombstone Handling

Configurable via `TombstoneSortOrder`:

| Setting | Behavior |
|---------|----------|
| `LAST` (default) | Tombstones sort after all non-null records |
| `FIRST` | Tombstones sort before all non-null records |
| `EQUAL` | Tombstones compare as equal to any record |

### Null Key Handling

Records with null keys are silently skipped — they cannot be grouped by key and would cause state store errors.

## Modules

| Module | Approach | Description |
|--------|----------|-------------|
| `resequence-starter` | Core library | Resequencing logic and Spring Boot auto-configuration |
| `sample-app` | Kafka Streams Processor API | Reference implementation using low-level Kafka Streams topology |
| `spring-integration-example` | Spring Integration Aggregator | Reference implementation using SI's Aggregator component |

Both `sample-app` and `spring-integration-example` produce identical output for identical input — they differ only in implementation approach.

## Kafka Streams Approach (`sample-app`)

### Architecture

```
Source Topic → [ResequenceProcessor + RocksDB State Store] → Sink Topic
                    ↑ wall-clock punctuator flushes every 2s
```

- Uses the **Processor API** (not the high-level DSL) for fine-grained control
- `ResequenceProcessor` buffers incoming records in a `KeyValueStore<K, List<BufferedRecord<V>>>` grouped by Kafka key
- A **wall-clock punctuator** fires every N seconds (configurable), iterates all keys, sorts each key's buffer using the comparator, and forwards records individually
- `BufferedRecord<V>` wraps each record with Kafka metadata (partition, offset, timestamp) needed for tiebreaking
- Supports generic key types (`Long`, `String`, `Integer`, etc.) and optional key re-mapping via `KeyMapper<K, KR>`
- Optional value enrichment via `BiConsumer<KR, V>`

### Strengths

- **Native Kafka Streams** — no additional framework layer; runs as part of a Kafka Streams topology
- **RocksDB state store** — handles large buffer volumes without memory pressure; automatically backed by changelog topics for fault tolerance
- **Exactly-once semantics** — available via Kafka Streams transactions
- **Fine-grained control** — direct access to processor context, record metadata, and scheduling

### Limitations

- Tightly coupled to Kafka Streams runtime
- Requires understanding the low-level Processor API
- Manual serde management (`BufferedRecordListSerde`)

## Spring Integration Approach (`spring-integration-example`)

### Architecture

```
Kafka Inbound Adapter → filter(null keys) → transform(wrap in BufferedRecord)
  → Aggregator(correlate by key, timeout release, sort in output processor)
  → split(sorted list → individual messages) → Kafka Outbound Handler
```

- Uses Spring Integration's **Aggregator** component — NOT the Resequencer (see below)
- Inbound Kafka adapter exposes partition, offset, and timestamp as message headers (`KafkaHeaders`)
- A `.handle()` step filters null keys and wraps each payload in `BufferedRecord`, capturing Kafka metadata from headers
- The **Aggregator** groups messages by the original Kafka key (via `correlationStrategy`)
- `groupTimeout` triggers release — functionally equivalent to the Kafka Streams wall-clock punctuator
- A custom `MessageGroupProcessor` sorts the group using the same `ResequenceComparator`
- `.split()` breaks the sorted list back into individual messages for the outbound adapter

### Concept Mapping

| Kafka Streams Concept | Spring Integration Equivalent | Notes |
|----------------------|------------------------------|-------|
| Record key grouping | `correlationStrategy` | Both group messages by the Kafka record key |
| Wall-clock punctuator (flush every N seconds) | `groupTimeout` (release after N ms) | Both are wall-clock time-based. SI uses a poller internally, KStreams uses a scheduled callback. Functionally equivalent — "flush what you have after N time" |
| `Comparator<BufferedRecord<V>>` sorting during flush | `MessageGroupProcessor` sorting during release | Identical comparator logic. SI wraps it in a `MessageGroupProcessor`; KStreams calls it directly in `flushAll()` |
| `KeyValueStore` (RocksDB, changelog-backed) | `MessageStore` (in-memory default, JDBC/Redis/Mongo optional) | KStreams' state store is fault-tolerant by default. SI's `SimpleMessageStore` is in-memory and lost on restart — production use requires a persistent `MessageStore` |
| `context().recordMetadata()` (partition, offset) | `KafkaHeaders.RECEIVED_PARTITION`, `KafkaHeaders.OFFSET` | Same metadata, different access pattern. KStreams: via processor context. SI: via message headers |
| Forward individual records in `flushAll()` | `.split()` after Aggregator | KStreams forwards records one-by-one in a loop. SI's Aggregator emits a single message per group — `.split()` breaks it back into individuals |
| `KeyMapper<K, KR>` + `BiConsumer<KR, V>` | Header manipulation + enrichment in output processor | Same concept: transform the key, optionally enrich the value |
| Null key → `return` in `process()` | `.handle()` returning `null` | Both skip records with null keys before buffering |

### Why Aggregator, Not Resequencer?

This is the key insight of this project.

**Spring Integration's [Resequencer](https://docs.spring.io/spring-integration/reference/resequencer.html)** is designed for a specific scenario: an upstream **Splitter** breaks a message into N parts, stamping each with `sequenceNumber` (1, 2, 3...) and `sequenceSize` headers. The Resequencer holds messages until it can release them in sequence order, waiting for gaps to fill.

**Our use case doesn't fit** because:

1. **No pre-existing sequence numbers.** Our ordering is derived from business logic (operation type → payload timestamp → Kafka metadata). Producers don't stamp sequence numbers — the correct order is only known after comparing all buffered messages.

2. **Chicken-and-egg problem.** To assign `sequenceNumber` headers, we'd need to sort first. But sorting is exactly what the Resequencer is supposed to do. If we sort first (in a pre-processing step) and then assign sequence numbers, the Resequencer is just a pass-through — it adds no value.

3. **No known sequence size.** The Resequencer works best when `sequenceSize` is known — it can detect completion. In our streaming use case, messages arrive continuously; we don't know how many messages per key will arrive in a given window.

4. **What about using both (Aggregator → Resequencer)?** Redundant. If the Aggregator's output processor already sorts messages using our comparator, feeding them to a Resequencer with assigned sequence numbers would just pass them through in the same order.

**The Aggregator is the right fit** because it provides:
- **Custom correlation** — group by any strategy (Kafka key in our case)
- **Timeout-based release** — release whatever has been buffered after N ms, no sequence numbers needed
- **Custom output processing** — sort the group using any comparator, transform keys, enrich values

### Strengths

- Familiar Spring Integration DSL and programming model
- Decoupled from Kafka Streams runtime — could adapt to other message sources (JMS, AMQP, etc.)
- No custom serde needed — Spring handles serialization
- Simpler configuration (no topology builder, state store, or serde wiring)

### Limitations

- **In-memory message store by default** — `SimpleMessageStore` is lost on restart. Production use requires JDBC/Redis `MessageStore`, which adds infrastructure. (Kafka Streams' RocksDB store is fault-tolerant out of the box via changelog topics.)
- **`groupTimeout` precision** — SI uses a poller internally, which may be less precise than Kafka Streams' `PunctuationType.WALL_CLOCK_TIME` scheduler
- **Tombstone handling** — SI `Message` payloads are typically non-null. Requires wrapping tombstones in `BufferedRecord` (where `record` field is null) and explicit null handling at the outbound stage. Kafka Streams handles null values natively.
- **Additional framework layer** — adds Spring Integration on top of Kafka, vs. Kafka Streams which is Kafka-native

## When to Use Which

| Scenario | Recommended Approach | Why |
|----------|---------------------|-----|
| High-volume Kafka-native pipeline | Kafka Streams (`sample-app`) | RocksDB handles large buffers; changelog-backed fault tolerance; exactly-once semantics |
| Already using Spring Integration | SI Aggregator (`spring-integration-example`) | Fits naturally into existing SI flows; no need to introduce Kafka Streams |
| Need fault-tolerant state without extra infrastructure | Kafka Streams | State store backed by changelog topics automatically |
| Multi-protocol message sources (JMS, AMQP, Kafka) | Spring Integration | Transport-agnostic; swap channel adapters |
| Need exactly-once processing guarantees | Kafka Streams | Native transaction support |
| Small buffer sizes, simple deployments | Either | Both work well; choose based on team familiarity |
| Custom ordering without sequence numbers | Either | Both support pluggable comparators (directly in KStreams, via output processor in SI) |

## Getting Started

```bash
# Build everything
./gradlew clean build

# Run all tests
./gradlew test

# Run sample-app tests (Kafka Streams approach)
./gradlew :sample-app:test

# Run spring-integration-example tests (SI approach)
./gradlew :spring-integration-example:test

# Run sample app
./gradlew :sample-app:bootRun
```

## Technologies

- **Java 21** (enforced via Gradle toolchain)
- **Spring Boot 4.0.1** with Spring Kafka
- **Kafka Streams** (sample-app) / **Spring Integration Kafka** (spring-integration-example)
- **Gradle** with Kotlin DSL
- **Testing:** Spock 2.4+ with Groovy 5.x, embedded Kafka via `@EmbeddedKafka`

## References

- [Resequencer Pattern (Enterprise Integration Patterns)](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html)
- [Spring Integration Resequencer Reference](https://docs.spring.io/spring-integration/reference/resequencer.html)
- [Spring Integration Aggregator Reference](https://docs.spring.io/spring-integration/reference/aggregator.html)
- [Kafka Streams Processor API](https://kafka.apache.org/documentation/streams/developer-guide/processor-api.html)
