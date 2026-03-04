# Resequence Starter

This module contains the core logic for the Resequence EIP implementation on top of Kafka Streams.

## Features
- Drop-in Spring Boot Starter.
- Configurable resequencing window and timeout.
- State store management for buffering messages.

## Usage
Add this dependency to your Spring Boot Kafka Streams application (not yet published to user repo):

```kotlin
implementation(project(":resequence-starter"))
```

## Using Without Spring Kafka

`ResequenceProcessor` is a plain Kafka Streams processor and can be used directly in a non-Spring app.

```java
Properties props = new Properties();
props.put(StreamsConfig.APPLICATION_ID_CONFIG, "my-resequence-app");
props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(StreamsConfig.STATE_DIR_CONFIG, "/var/lib/my-resequence-app/kafka-streams-state");
// ... add serde + processing guarantees as needed

Topology topology = new Topology();
topology.addStateStore(
    Stores.keyValueStoreBuilder(
        Stores.persistentKeyValueStore("resequence-buffer"),
        Serdes.String(),
        bufferedRecordListSerde
    )
);
topology.addSource("source", keyDeserializer, valueDeserializer, "input-topic");
topology.addProcessor("resequencer", () -> processor, "source");
topology.connectProcessorAndStateStores("resequencer", "resequence-buffer");
topology.addSink("sink", "output-topic", keySerializer, valueSerializer, "resequencer");

KafkaStreams streams = new KafkaStreams(topology, props);
streams.start();
```

### `state.dir` guidance

- Production: set `StreamsConfig.STATE_DIR_CONFIG` to a stable, writable local-disk path.
- Embedded/local test environments where brokers are recreated: use an isolated `state.dir` per run to prevent stale checkpoint offsets from causing changelog restore failures (for example, `OffsetOutOfRangeException`).
