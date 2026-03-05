# Resequence Observability Contract

**Created**: 2026-03-04
**Confidence Score**: 96/100
**Status**: Draft

## Problem Statement

The `resequence-starter` library has no observability surface. Users cannot see how many records are buffered, how long flushes take, or whether records are being silently dropped due to null keys. This makes it difficult to operate the resequencer in production — problems like buffer growth, flush latency spikes, or data quality issues are invisible without external tooling.

Library users (particularly on Confluent Platform) may already have Kafka Streams infrastructure metrics via the OTel Java agent or JMX, but those metrics don't surface resequencer-specific business events: buffer depth per key, records sorted and forwarded per flush, or null-key drops. There is no hook for users who want to wire in their own observability backend.

Without an observability surface, users have no way to verify the resequencer is behaving correctly in production, tune flush intervals based on real data, or detect data quality issues early.

## Goals

1. Add a `ResequenceEventListener` interface to `resequence-starter` with callbacks for all significant processing events, with a no-op default so existing users are unaffected.
2. Add `opentelemetry-api` as a `compileOnly` dependency and provide an `OtelResequenceEventListener` implementation that emits counters, gauges, and histograms — active when the OTel API is on the runtime classpath.
3. Wire the listener into `ResequenceProcessor` via constructor (framework-agnostic) and via Spring auto-configuration (Spring Boot users get automatic wiring).
4. Update `sample-app` to demonstrate the OTel listener in action and log an accumulated metrics summary on application shutdown.

## Success Criteria

- [ ] `ResequenceEventListener` interface exists with callbacks: `onRecordBuffered`, `onFlushStarted(isFullScan)`, `onFlushCompleted(keysCount, recordsCount)`, `onKeyFlushed(key, recordCount)`, `onNullKeyDropped`
- [ ] A static `ResequenceEventListener.noOp()` factory exists; `ResequenceProcessor` defaults to it when no listener is provided
- [ ] `ResequenceProcessor` accepts a `ResequenceEventListener` as a constructor parameter (backward-compatible — existing constructors continue to work)
- [ ] `OtelResequenceEventListener` implements `ResequenceEventListener` using `opentelemetry-api` instruments: counter for records buffered, counter for records forwarded, counter for null-key drops, histogram for flush duration, gauge for buffer depth at flush time
- [ ] `ResequenceAutoConfiguration` registers an `OtelResequenceEventListener` bean when `opentelemetry-api` is on the classpath and `resequence.otel.enabled` is not `false`; falls back to no-op otherwise
- [ ] `@ConditionalOnMissingBean(ResequenceEventListener.class)` ensures user-provided beans take precedence
- [ ] `resequence.otel.enabled=false` disables OTel auto-configuration
- [ ] `sample-app` wires the listener and logs metric totals on `ContextClosedEvent`
- [ ] `OtelResequenceEventListener` is verified via Spock tests using OTel's `InMemoryMetricReader` (`opentelemetry-sdk-testing` test dependency): assert counter values, gauge readings, and histogram recordings after running processor operations through `TopologyTestDriver`
- [ ] Existing `ResequenceProcessorSpec` tests continue to pass without modification
- [ ] `resequence-starter` build compiles cleanly without OTel SDK on the runtime classpath
- [ ] `sample-app` can be started via `./gradlew :sample-app:bootRun` and the console shows accumulated metric totals logged on shutdown (records buffered, records forwarded, null keys dropped, flush count)

## Scope Boundaries

### In Scope

- `ResequenceEventListener` interface and `noOp()` implementation in `resequence-starter`
- `OtelResequenceEventListener` using `opentelemetry-api` (`compileOnly`) in `resequence-starter`
- Updated `ResequenceProcessor` constructors accepting a listener
- Updated `ResequenceAutoConfiguration` with OTel conditional bean registration and `resequence.otel.enabled` property
- Updated `ResequenceProperties` to include `otel.enabled` flag
- `sample-app` wiring: OTel SDK dependency, listener bean, shutdown logging via `ApplicationListener<ContextClosedEvent>`
- Spock tests for the listener interface and OTel listener (with a mock/test OTel SDK)

### Out of Scope

- Micrometer integration — OTel is the chosen mechanism; Micrometer bridge is a future consideration
- OpenTelemetry tracing / spans — only metrics (counters, gauges, histograms); traces deferred
- A separate `resequence-starter-otel` module — `compileOnly` in core keeps it simple for now
- Exposing metrics via Spring Boot Actuator in sample-app — shutdown log summary is sufficient
- Confluent-specific metric forwarding — covered by platform-level OTel agent

### Future Considerations

- Composite listener (allow multiple listeners to be chained)
- Micrometer bridge module for users who prefer Micrometer over OTel directly
- OTel tracing: wrap flush cycle as a span for distributed trace context
- Per-partition metric dimensions (if multi-partition use cases emerge)

## Execution Plan

_Added during Phase 5 handoff. Pick up this contract cold and know exactly how to execute._

### Dependency Graph

```
Phase 1: ResequenceEventListener interface + processor wiring
  └── Phase 2: OTel implementation + auto-configuration + sample-app
```

### Execution Steps

**Strategy**: Sequential

1. **Phase 1** — Listener interface and processor wiring _(blocking)_
   ```bash
   /execute-spec docs/ideation/resequence-observability/spec-phase-1.md
   ```

2. **Phase 2** — OTel implementation, auto-config, and sample-app _(blocked by Phase 1)_
   ```bash
   /execute-spec docs/ideation/resequence-observability/spec-phase-2.md
   ```

---

_This contract was generated from brain dump input. Review and approve before proceeding to specification._
