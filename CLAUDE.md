# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Spring Boot Starter library implementing the **Resequence Enterprise Integration Pattern** for Kafka Streams. Messages arriving out-of-order are buffered and re-emitted in the correct sequence based on configurable ordering logic.

**Modules:**
- `resequence-starter` - Core library (`com.snekse.kafka.streams.resequence`): `ResequenceProcessor`, `BufferedRecord`, `KeyMapper`, `TombstoneSortOrder`, `BufferedRecordListSerde`, `ResequenceProperties`, auto-configuration
- `sample-app` - Reference implementation (`com.example.sampleapp`): domain-specific `ResequenceComparator`, `SampleRecord`, topology wiring

## Build Commands

```bash
./gradlew clean build          # Build with tests
./gradlew test                  # Run all tests
./gradlew :sample-app:test      # Run sample-app tests only
./gradlew :sample-app:bootRun   # Run sample app (requires Kafka or uses embedded)
```

## Technology Stack

- **Java 21** (enforced via toolchain)
- **Spring Boot 4.0.1** with Spring Kafka
- **Gradle** with Kotlin DSL
- **Testing**: Spock 2.4+ with Groovy 5.x, embedded Kafka via `@EmbeddedKafka`

## Architecture

### Resequencing Pattern

The core pattern buffers out-of-order messages using a wall-clock punctuator, then re-emits them sorted by a pluggable `Comparator`. The `ResequenceComparator` in the sample app orders by:
1. Operation type: CREATE (0) < UPDATE (1) < DELETE (2)
2. Fallback to payload timestamp
3. Fallback to Kafka metadata (offset within partition, timestamp across partitions)

### Kafka Streams Topology

Source topic (`sample-topic`) → Resequencing processor with state store → Sink topic (`sample-topic-resequenced`)

### Key Types

**Starter types** (`com.snekse.kafka.streams.resequence`):
- `ResequenceProcessor` - Kafka Streams processor that buffers, sorts, and forwards records
- `BufferedRecord<T>` - Wraps records with Kafka metadata (partition, offset, timestamp) for ordering
- `KeyMapper<K, KR>` - Functional interface for key re-mapping
- `TombstoneSortOrder` - Enum: FIRST, EQUAL, LAST
- `BufferedRecordListSerde<T>` - Jackson-based serde for state store
- `ResequenceProperties` - Spring Boot configuration properties

**Sample-app types** (`com.example.sampleapp`):
- `SampleRecord` - Domain object with clientId, entityType, operation, timestamp
- `ResequenceComparator` - Domain-specific comparator implementing the ordering logic
- `EntityType` enum: Parent, ChildA, ChildB

## Code Style Rules

**Java:**
- Avoid fully-qualified names; use imports

**Groovy/Spock:**
- Follow `.agent/skills/self-review-before-finishing-tasks/spock-tests-guide.md` when writing and planning Spock tests
- Test files must be named `*Spec.groovy`

## Documentation

- `docs/adr/` - Architectural Decision Records

## Agent Infrastructure

The `.agent/` directory contains prompts and skills for AI-assisted development:
- `.agent/prompts/` - Implementation prompts for the resequencer
- `.agent/skills/self-review-before-finishing-tasks/` - Style guides to review before completing tasks
