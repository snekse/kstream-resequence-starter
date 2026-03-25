# resequence-core

Zero-dependency core library implementing the [Resequence Enterprise Integration Pattern](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html) for Kafka Streams.

## Overview

Buffers out-of-order Kafka messages and re-emits them in the correct sequence based on a pluggable `Comparator`. The only runtime dependency is `org.apache.kafka:kafka-streams` — no Spring, no Jackson, no other frameworks.

## Usage

```java
var resequencer = Resequencer.<String, MyRecord>builder()
    .comparator(myComparator)
    .valueSerde(myRecordSerde)
    .keySerde(Serdes.String())
    .build();

// Wire into your topology
resequencer.addTo(topology, "resequencer", "source");
```

## Builder Defaults

| Property | Default | Description |
|----------|---------|-------------|
| `flushInterval` | `2s` | How often the punctuator flushes buffered records |
| `stateStoreName` | `resequence-buffer` | Name of the backing state store |
| `keyMapper` | identity | Maps input keys to output keys (optional) |
| `valueMapper` | no-op | Transforms buffered records to output values (optional) |

## Key Types

- `Resequencer` — Entry point with fluent builder API
- `BufferedRecord<V>` — Wraps records with Kafka metadata (partition, offset, timestamp)
- `ResequenceComparator<V>` — Comparator for `BufferedRecord<V>` that defines ordering
- `BufferedRecordListSerde<V>` — Binary serde for the state store (delegates to your `Serde<V>`)
