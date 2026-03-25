# resequence-spring-boot-starter

Optional Spring Boot starter that provides YAML-driven configuration and auto-wired beans for [resequence-core](../resequence-core).

## Overview

This module bridges `resequence-core` with Spring Boot auto-configuration. It binds `resequence.*` YAML properties to a pre-configured `Resequencer.Builder` bean, letting Spring Boot users configure the resequencer without manual builder setup.

## Usage

Add the dependency (this transitively includes `resequence-core`):

```kotlin
implementation("com.snekse:resequence-spring-boot-starter")
```

Configure via `application.yml`:

```yaml
resequence:
  state-store-name: my-buffer
  flush-interval: 5s
  tombstone-sort-order: LAST
```

Inject and use the pre-configured builder:

```java
@Bean
public Resequencer.Definition<String, MyRecord, String, MyRecord> resequencer(
        Resequencer.Builder<Object, Object, Object, Object> builder) {
    return builder
        .comparator(myComparator)
        .valueSerde(myRecordSerde)
        .keySerde(Serdes.String())
        .build();
}
```

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `resequence.state-store-name` | `resequence-buffer` | Name of the backing state store |
| `resequence.flush-interval` | `2s` | How often the punctuator flushes buffered records |
| `resequence.tombstone-sort-order` | `LAST` | Where tombstones sort: `FIRST`, `EQUAL`, or `LAST` |

## Auto-Configuration

- `ResequenceProperties` — `@ConfigurationProperties` with constructor binding
- `ResequenceAutoConfiguration` — Exposes a `Resequencer.Builder` bean pre-configured with property values
- The builder bean uses `@ConditionalOnMissingBean`, so you can override it entirely if needed
