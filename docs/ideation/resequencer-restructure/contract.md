# Resequencer Restructure Contract

**Created**: 2026-03-24
**Confidence Score**: 95/100
**Status**: Approved

## Project Vision

This repository's purpose is to provide a **drop-in library for implementing the [Resequence Enterprise Integration Pattern](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html) in any Kafka Streams application**. The library will be published to Maven Central so that any team — regardless of framework choice — can add a single dependency and resequence a Kafka topic based on user-defined ordering criteria.

The core value proposition: **minimal work for the consuming developer**. A user should only need to:
1. Add the dependency
2. Define their ordering logic (a `Comparator`)
3. Configure a few properties (flush interval, tombstone behavior)
4. Wire the processor into their topology

Everything else — buffering, state store management, serde handling, dirty-key optimization, tombstone positioning — is handled by the library with sensible defaults.

**Every decision in this restructure must be evaluated against this vision.** If a design choice makes the library harder to adopt, less portable, or more opinionated than necessary, it's wrong — even if it's technically elegant.

## Problem Statement

The existing implementation largely achieves the vision above, but its architecture limits who can adopt it. The core library depends on `spring-boot-autoconfigure` and Jackson at runtime, meaning any Kafka Streams application — even those without Spring — pulls in Spring Framework classes and Jackson for JSON serialization of internal state. This narrows the library's audience to Spring Boot users and forces Jackson onto teams that standardize on Avro, Protobuf, or other formats.

Additionally, the processor's construction API uses raw constructors with unchecked generic casts, creating runtime risks: a misconfigured `KeyMapper` can cause `ClassCastException` that kills Kafka Streams threads (issue #21). Configuration requires understanding internal constructor signatures rather than a discoverable, self-documenting API.

The library needs to be restructured into a zero-dependency core (Kafka Streams only) with an optional Spring Boot starter, a type-safe builder API, and a serde that works with any serialization format — all in service of making the library adoptable by the widest possible audience with the least possible friction.

## Goals

1. **Zero unnecessary runtime dependencies in core** — The published `resequence-core` artifact depends only on `org.apache.kafka:kafka-streams`. No Spring, no Jackson, no other frameworks. Any Kafka Streams app can use it.
2. **Format-agnostic state store serialization** — Replace Jackson-based `BufferedRecordListSerde` with a composite binary serde that delegates value serialization to the user's existing `Serde<V>`. Works with Avro, Protobuf, JSON, or any custom format — the library adapts to the user's stack, not the other way around.
3. **Type-safe builder API** — Fluent builder for `ResequenceProcessor` that enforces constraints at compile time (e.g., if `K != KR`, a `KeyMapper` is required). Sensible defaults for flush interval, tombstone sort order, and state store name. A new user should be productive in minutes, not hours.
4. **Optional Spring Boot auto-configuration** — Separate `resequence-spring-boot-starter` module bridges the builder API with `@ConfigurationProperties` for YAML-driven configuration. Spring Boot users get the convenience they expect; non-Spring users aren't penalized.
5. **Proven by sample app** — Sample application demonstrates real-world usage via the Spring Boot starter, with integration tests using embedded Kafka. Serves as living documentation for adopters.

## Success Criteria

- [ ] `resequence-core` compiles and runs with only `kafka-streams` on the classpath (verified by Gradle dependency check)
- [ ] `BufferedRecordListSerde` accepts `Serde<V>` and round-trips `List<BufferedRecord<V>>` through a binary format without Jackson
- [ ] Builder API: `ResequenceProcessor.builder()` provides fluent configuration with sensible defaults (`flushInterval=2s`, `tombstoneSortOrder=LAST`, `stateStoreName="resequence-buffer"`)
- [ ] Builder enforces type safety: omitting `keyMapper` when `K != KR` is a compile-time error (or at minimum, a clear build-time/startup-time failure)
- [ ] `resequence-spring-boot-starter` maps `resequence.*` YAML properties to the builder
- [ ] All existing unit tests in the core module pass (adapted to new APIs)
- [ ] Sample app integration tests pass with embedded Kafka, demonstrating end-to-end resequencing
- [ ] Null key records are silently skipped (existing behavior preserved)
- [ ] Tombstone handling (FIRST, EQUAL, LAST) works correctly with the new serde
- [ ] Key mapping and value mapping remain optional with identity defaults
- [ ] Dirty-key flush optimization is preserved

## Scope Boundaries

### In Scope

- Restructure into 3 modules: `resequence-core`, `resequence-spring-boot-starter`, `sample-app`
- Replace Jackson-based serde with composite binary serde accepting `Serde<V>`
- Builder pattern for `ResequenceProcessor` with sensible defaults
- Type safety for key/value mapper configuration (addresses #21, #7)
- Spring Boot auto-configuration in the starter module
- Update sample app to use the new starter module
- Migrate and adapt all existing tests
- Format-agnostic serialization (addresses #33)

### Out of Scope

- KStream sink convenience API (#28) — processor already supports topology chaining via `context().forward()`; convenience wiring is a follow-up
- Observability / metrics (#41) — valuable but separate concern, future phase
- Maven Central publishing setup — build/release infrastructure is a separate task
- Spring Integration comparison or alternative implementations
- Multi-topic resequencing in a single processor instance

### Future Considerations

- Observability: `ResequenceEventListener` with OpenTelemetry integration (#41)
- KStream sink convenience in the Spring Boot starter (#28)
- Maven Central publishing with proper POM metadata and signing
- Gradle module metadata for variant-aware dependency resolution
- Documentation site or expanded README with usage guides

## Execution Plan

### Dependency Graph

```
Phase 1: Core Module (resequence-core)
  └── Phase 2: Spring Boot Starter (resequence-spring-boot-starter)
        └── Phase 3: Sample App Update
```

All phases are strictly sequential — each depends on the previous.

### Execution Steps

**Strategy**: Sequential

1. **Phase 1 — Core Module** _(blocking, estimated L)_
   Create `resequence-core` with zero Spring/Jackson deps, binary serde, builder API.
   ```
   /ideation:execute-spec docs/ideation/resequencer-restructure/spec-phase-1.md
   ```

2. **Phase 2 — Spring Boot Starter** _(blocked by Phase 1, estimated S)_
   Move Spring config from `resequence-starter/` shell, create auto-configuration, delete old module.
   ```
   /ideation:execute-spec docs/ideation/resequencer-restructure/spec-phase-2.md
   ```

3. **Phase 3 — Sample App Update** _(blocked by Phase 2, estimated M)_
   Update sample-app to use new modules and builder API, verify end-to-end with embedded Kafka.
   ```
   /ideation:execute-spec docs/ideation/resequencer-restructure/spec-phase-3.md
   ```

### Git History Strategy

Files are `git mv`'d (not deleted and recreated) across all phases to preserve git history:
- Phase 1: `git mv` core files from `resequence-starter/` → `resequence-core/`
- Phase 2: `git mv` Spring config from `resequence-starter/` → `resequence-spring-boot-starter/`
- Phase 2: Delete now-empty `resequence-starter/`

### Resolved Issues

After all phases complete, the following issues are addressed:
- **#33** (format-agnostic serde) — solved by composite binary serde
- **#21** (null keyMapper DoS) — solved by builder's identity KeyMapper default
- **#7** (optional re-keying) — solved by builder API with identity default
- **#36 / #30** (eliminate Spring from core) — solved by 3-module structure

---

_This contract was generated from brain dump input and approved on 2026-03-24._
