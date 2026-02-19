# Kafka Streams Resequence Starter

This project provides a Spring Boot Starter library to implement the **Resequence Enterprise Integration Pattern** using Kafka Streams.

## Goal

The simpler goal is to resequence messages based on a sequence number or timestamp without the overhead of full Spring Integration, specifically tailored for Kafka Streams environments.

See [Resequencer Pattern](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html).

---

Beyond the core starter, the project also includes a reference implementation.

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

The multi-level comparator determines the correct message order:

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

- **`resequence-starter`** — Core library with resequencing logic and Spring Boot auto-configuration
- **`sample-app`** — Reference implementation demonstrating the starter using the Kafka Streams Processor API

## Architecture

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

## Design Rationale: Why Kafka Streams over Spring Integration?

An alternative approach is to use Spring Integration's Aggregator component for resequencing. While viable, Kafka Streams was chosen for this starter because:

- **Fault-tolerant state out of the box** — Kafka Streams' RocksDB state store is automatically backed by changelog topics. SI's default `SimpleMessageStore` is in-memory and lost on restart; production use requires JDBC/Redis `MessageStore`, adding infrastructure.
- **Kafka-native** — no additional framework layer between your application and Kafka.
- **Exactly-once semantics** — available via Kafka Streams transactions.

Spring Integration's strengths (familiar DSL, transport-agnostic, simpler configuration) make it a good fit if you're already using SI or need multi-protocol support (JMS, AMQP, etc.).

### Why Aggregator, Not Resequencer?

This is a key insight for anyone considering the Spring Integration approach.

**Spring Integration's [Resequencer](https://docs.spring.io/spring-integration/reference/resequencer.html)** is designed for a specific scenario: an upstream **Splitter** breaks a message into N parts, stamping each with `sequenceNumber` (1, 2, 3...) and `sequenceSize` headers. The Resequencer holds messages until it can release them in sequence order, waiting for gaps to fill.

**This use case doesn't fit** because:

1. **No pre-existing sequence numbers.** The ordering is derived from business logic (operation type → payload timestamp → Kafka metadata). Producers don't stamp sequence numbers — the correct order is only known after comparing all buffered messages.

2. **Chicken-and-egg problem.** To assign `sequenceNumber` headers, we'd need to sort first. But sorting is exactly what the Resequencer is supposed to do. If we sort first (in a pre-processing step) and then assign sequence numbers, the Resequencer is just a pass-through — it adds no value.

3. **No known sequence size.** The Resequencer works best when `sequenceSize` is known — it can detect completion. In a streaming use case, messages arrive continuously; we don't know how many messages per key will arrive in a given window.

4. **What about using both (Aggregator → Resequencer)?** Redundant. If the Aggregator's output processor already sorts messages using the comparator, feeding them to a Resequencer with assigned sequence numbers would just pass them through in the same order.

The **Aggregator** is the right SI component because it provides custom correlation (group by any strategy), timeout-based release (no sequence numbers needed), and custom output processing (sort using any comparator).

## Getting Started

```bash
# Build everything
./gradlew clean build

# Run all tests
./gradlew test

# Run sample-app tests
./gradlew :sample-app:test

# Run sample app
./gradlew :sample-app:bootRun
```

## Technologies

- **Java 21** (enforced via Gradle toolchain)
- **Spring Boot 4.0.1** with Spring Kafka
- **Kafka Streams** Processor API
- **Gradle** with Kotlin DSL
- **Testing:** Spock 2.4+ with Groovy 5.x, embedded Kafka via `@EmbeddedKafka`

## References

- [Resequencer Pattern (Enterprise Integration Patterns)](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html)
- [Spring Integration Resequencer Reference](https://docs.spring.io/spring-integration/reference/resequencer.html)
- [Spring Integration Aggregator Reference](https://docs.spring.io/spring-integration/reference/aggregator.html)
- [Kafka Streams Processor API](https://kafka.apache.org/documentation/streams/developer-guide/processor-api.html)
