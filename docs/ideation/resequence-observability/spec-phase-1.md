# Implementation Spec: Resequence Observability - Phase 1

**Contract**: ./contract.md
**Estimated Effort**: S

## Technical Approach

Add a `ResequenceEventListener` interface to the `resequence-starter` module. The interface defines callbacks for every significant processing event inside `ResequenceProcessor`. A static `noOp()` factory returns a no-allocation, do-nothing implementation so that the processor's default behavior is unchanged.

Update `ResequenceProcessor` to accept a `ResequenceEventListener` as an optional constructor parameter. The two existing constructors are preserved exactly (backward-compatible) and internally chain to a new three-arg-plus-listener overload that defaults to `noOp()`. Listener calls are inserted at each meaningful event point in the processor.

No new dependencies are introduced in this phase. The only new source files are the interface and its test spec.

## Feedback Strategy

**Inner-loop command**: `./gradlew :resequence-starter:test`

**Playground**: Test suite via `TopologyTestDriver` (same pattern as `ResequenceProcessorSpec`).

**Why this approach**: Changes are entirely in processor logic; the existing test driver setup is already fast and exercises the full event flow.

## File Changes

### New Files

| File Path | Purpose |
| --- | --- |
| `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/listener/ResequenceEventListener.java` | Listener interface with 5 callbacks and a `noOp()` factory |
| `resequence-starter/src/test/groovy/com/snekse/kafka/streams/resequence/listener/ResequenceEventListenerSpec.groovy` | Spock spec verifying each listener callback fires with correct arguments |

### Modified Files

| File Path | Changes |
| --- | --- |
| `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/processor/ResequenceProcessor.java` | Add `listener` field; add new constructor overload; insert `listener.on*()` calls at 5 event points |

## Implementation Details

### ResequenceEventListener Interface

**Pattern to follow**: `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/processor/ValueMapper.java` (functional interface with static factory)

**Overview**: Defines the observability surface for the processor. Uses `Object` for the key type to avoid making the interface generic — the listener is for observation only, not type-safe processing.

**Javadoc requirement**: Every method on the interface must have a full Javadoc comment explaining: (1) when the callback fires, (2) what each parameter means, and (3) any ordering guarantees (e.g., "called before the key is deleted from the state store"). This is a public API surface — treat the Javadocs as user-facing documentation. The class-level Javadoc should describe the intended use cases (observability, metrics, custom business logic) and note that implementations must be thread-safe if the processor is used in multi-threaded contexts (though KStreams processors are single-threaded per task).

```java
package com.snekse.kafka.streams.resequence.listener;

import com.snekse.kafka.streams.resequence.domain.BufferedRecord;

/**
 * Callback interface for observing significant events in a {@link com.snekse.kafka.streams.resequence.processor.ResequenceProcessor}.
 *
 * <p>Implementations can use this interface to collect metrics, emit telemetry, log diagnostic
 * information, or trigger custom business logic in response to resequencing events. A no-op
 * implementation is available via {@link #noOp()}.
 *
 * <p>Callback methods are invoked synchronously on the Kafka Streams processor thread.
 * Because Kafka Streams tasks are single-threaded, implementations do not need to be
 * thread-safe unless a single listener instance is shared across multiple processor instances.
 *
 * <p>Implementations should be lightweight and non-blocking. Expensive operations (e.g., remote
 * calls) should be performed asynchronously to avoid introducing latency into the processing loop.
 */
public interface ResequenceEventListener {

    /**
     * Called when a record has been appended to the resequence buffer for the given key.
     *
     * <p>This callback fires after the record is successfully written to the state store.
     * It is not called for records with null keys (see {@link #onNullKeyDropped()}).
     *
     * @param key    the record's input key (before any key mapping)
     * @param record the buffered record, including the original value and Kafka metadata
     *               (partition, offset, timestamp)
     */
    void onRecordBuffered(Object key, BufferedRecord<?> record);

    /**
     * Called at the beginning of each flush cycle, before any keys are processed.
     *
     * <p>This callback fires once per punctuator invocation. It is always followed by
     * a corresponding {@link #onFlushCompleted(int, int)} call.
     *
     * @param isFullScan {@code true} on the first flush after startup or partition rebalance,
     *                   when the processor performs a full state store scan to recover previously
     *                   buffered records; {@code false} for all subsequent flushes, which use
     *                   an efficient dirty-key lookup
     */
    void onFlushStarted(boolean isFullScan);

    /**
     * Called at the end of each flush cycle, after all dirty keys have been sorted, forwarded,
     * and removed from the state store.
     *
     * <p>This callback fires once per punctuator invocation, after all
     * {@link #onKeyFlushed(Object, int)} calls for this cycle have completed.
     *
     * @param keysCount    the number of distinct keys flushed in this cycle
     * @param recordsCount the total number of records forwarded across all keys in this cycle
     */
    void onFlushCompleted(int keysCount, int recordsCount);

    /**
     * Called after a single key's buffered records have been sorted and forwarded downstream,
     * and before the key's entry is deleted from the state store.
     *
     * <p>This callback fires once per distinct key per flush cycle. It is only called when
     * the key has at least one buffered record; keys with null or empty record lists are skipped.
     *
     * @param key         the input key (before any key mapping applied by the processor)
     * @param recordCount the number of records sorted and forwarded for this key
     */
    void onKeyFlushed(Object key, int recordCount);

    /**
     * Called when an incoming record has a null value, indicating a tombstone.
     *
     * <p>This callback fires in addition to {@link #onRecordBuffered(Object, BufferedRecord)}
     * when the record value is null. Tombstones are valid domain events — they are buffered and
     * forwarded downstream in sorted order according to the processor's {@link
     * com.snekse.kafka.streams.resequence.domain.TombstoneSortOrder} configuration.
     *
     * <p>The {@code record} parameter will have a null {@code record} field
     * ({@code bufferedRecord.getRecord() == null}) but will carry valid Kafka metadata
     * (partition, offset, timestamp).
     *
     * @param key    the record's input key
     * @param record the buffered tombstone record with Kafka metadata
     */
    void onTombstoneReceived(Object key, BufferedRecord<?> record);

    /**
     * Called when an incoming record is silently ignored because its key is null.
     *
     * <p>Null-key records cannot be stored in the Kafka Streams state store. The record
     * is not buffered and will not appear in the output topic. This callback provides
     * visibility into such data quality events.
     */
    void onRecordIgnored();

    /**
     * Returns a no-op implementation that does nothing for all callbacks.
     *
     * <p>This is the default listener used when no listener is explicitly provided to
     * {@link com.snekse.kafka.streams.resequence.processor.ResequenceProcessor}.
     *
     * @return a stateless no-op {@code ResequenceEventListener}
     */
    static ResequenceEventListener noOp() {
        return new ResequenceEventListener() {
            public void onRecordBuffered(Object key, BufferedRecord<?> record) {}
            public void onTombstoneReceived(Object key, BufferedRecord<?> record) {}
            public void onFlushStarted(boolean isFullScan) {}
            public void onFlushCompleted(int keysCount, int recordsCount) {}
            public void onKeyFlushed(Object key, int recordCount) {}
            public void onRecordIgnored() {}
        };
    }
}
```

**Key decisions**:
- `Object` key type avoids generic type parameter on the interface — callers only need it for logging/tagging, not type-safe access
- `noOp()` returns an anonymous class (not a lambda) so all five methods are satisfied in one block; a singleton constant would also be acceptable
- Javadoc on each method documents when it fires

**Implementation steps**:

1. Create the `listener` package directory under `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/`
2. Create `ResequenceEventListener.java` with the interface and `noOp()` factory above
3. Compile: `./gradlew :resequence-starter:compileJava`

**Feedback loop**:
- **Playground**: Compile check — no test needed yet for the interface alone
- **Experiment**: Confirm the class is resolvable from other packages
- **Check command**: `./gradlew :resequence-starter:compileJava`

---

### ResequenceProcessor — Listener Wiring

**Pattern to follow**: `resequence-starter/src/main/java/com/snekse/kafka/streams/resequence/processor/ResequenceProcessor.java` (existing constructor chaining pattern)

**Overview**: Add a `ResequenceEventListener` field and a new constructor that accepts it. The two existing constructors chain to the new one with `ResequenceEventListener.noOp()`. Listener calls are inserted at 6 points across `process()`, `flushAll()`, `flushViaFullScan()`, `flushViaDirtyKeys()`, and `flushKey()`.

```java
// New field (add with other finals)
private final ResequenceEventListener listener;

// Existing 3-arg constructor — unchanged signature, now chains to 6-arg
@SuppressWarnings({"unchecked", "unused"})
public ResequenceProcessor(ResequenceComparator<V> comparator, String stateStoreName, Duration flushInterval) {
    this(comparator, stateStoreName, flushInterval, null,
         (ValueMapper<KR, V, VR>) ValueMapper.noOp(), ResequenceEventListener.noOp());
}

// Existing 5-arg constructor — unchanged signature, now chains to 6-arg
@SuppressWarnings("unchecked")
public ResequenceProcessor(ResequenceComparator<V> comparator, String stateStoreName, Duration flushInterval,
                           KeyMapper<K, KR> keyMapper, ValueMapper<KR, V, VR> valueMapper) {
    this(comparator, stateStoreName, flushInterval, keyMapper, valueMapper, ResequenceEventListener.noOp());
}

// New 6-arg constructor — canonical
@SuppressWarnings("unchecked")
public ResequenceProcessor(ResequenceComparator<V> comparator, String stateStoreName, Duration flushInterval,
                           KeyMapper<K, KR> keyMapper, ValueMapper<KR, V, VR> valueMapper,
                           ResequenceEventListener listener) {
    this.comparator = comparator;
    this.stateStoreName = stateStoreName;
    this.flushInterval = flushInterval;
    this.keyMapper = keyMapper;
    this.valueMapper = valueMapper != null ? valueMapper : (ValueMapper<KR, V, VR>) ValueMapper.noOp();
    this.listener = listener != null ? listener : ResequenceEventListener.noOp();
}
```

**Listener call sites** (6 total):

```java
// ── process() ────────────────────────────────────────────────────────────────

@Override
public void process(Record<K, V> record) {
    K key = record.key();
    V value = record.value();

    // Call site 1: null key — record is silently ignored
    if (key == null) {
        listener.onRecordIgnored();
        return;
    }

    // `buffered` is defined here — it holds the wrapped value with Kafka metadata
    BufferedRecord<V> buffered = BufferedRecord.<V>builder()
            .record(value)
            .partition(context().recordMetadata().map(RecordMetadata::partition).orElse(-1))
            .offset(context().recordMetadata().map(RecordMetadata::offset).orElse(-1L))
            .timestamp(record.timestamp())
            .build();

    List<BufferedRecord<V>> records = store.get(key);
    if (records == null) {
        records = new ArrayList<>();
    }
    records.add(buffered);
    store.put(key, records);
    dirtyKeys.add(key);

    // Call site 2: tombstone — null value buffered as a domain event (fires before onRecordBuffered)
    if (value == null) {
        listener.onTombstoneReceived(key, buffered);
    }

    // Call site 3: record successfully buffered (fires for all records, including tombstones)
    listener.onRecordBuffered(key, buffered);
}

// ── flushAll() ───────────────────────────────────────────────────────────────

// Call site 4: flush cycle starts
// `int[] totals` is created here and threaded through to accumulate counts
private void flushAll(long timestamp) {
    listener.onFlushStarted(needsRecoveryScan);
    int[] totals = {0, 0}; // [keysCount, recordsCount]
    if (needsRecoveryScan) {
        flushViaFullScan(timestamp, totals);  // signature updated — see below
        needsRecoveryScan = false;
    } else {
        flushViaDirtyKeys(timestamp, totals); // signature updated — see below
    }
    dirtyKeys.clear();
    listener.onFlushCompleted(totals[0], totals[1]); // call site 6 (see flushKey)
}

// ── flushViaFullScan() / flushViaDirtyKeys() — updated signatures ────────────
// Both methods gain an `int[] totals` parameter and thread it through to flushKey.

private void flushViaFullScan(long timestamp, int[] totals) {
    try (KeyValueIterator<K, List<BufferedRecord<V>>> iter = store.all()) {
        while (iter.hasNext()) {
            var entry = iter.next();
            flushKey(entry.key, entry.value, timestamp, totals);
        }
    }
}

private void flushViaDirtyKeys(long timestamp, int[] totals) {
    for (K key : dirtyKeys) {
        List<BufferedRecord<V>> records = store.get(key);
        flushKey(key, records, timestamp, totals);
    }
}

// ── flushKey() ───────────────────────────────────────────────────────────────

@SuppressWarnings("unchecked")
private void flushKey(K key, List<BufferedRecord<V>> records, long timestamp, int[] totals) {
    if (records == null || records.isEmpty()) return;

    records.sort(comparator);
    KR outputKey = keyMapper != null ? keyMapper.map(key) : (KR) key;

    for (BufferedRecord<V> br : records) {
        VR mappedValue = valueMapper.mapValue(outputKey, br);
        context().forward(new Record<>(outputKey, mappedValue, timestamp));
    }

    // Call site 5: key fully flushed — fires BEFORE store.delete (matches javadoc contract)
    listener.onKeyFlushed(key, records.size());
    store.delete(key);

    // Accumulate totals for onFlushCompleted (call site 6, fired in flushAll)
    totals[0]++;
    totals[1] += records.size();
}
```

**Key decisions**:
- `onTombstoneReceived` fires before `onRecordBuffered` for null-value records — "specific before generic" lets listeners short-circuit if they only care about tombstones, while `onRecordBuffered` still fires so total-count listeners don't need special-casing
- `onRecordIgnored` replaces `onNullKeyDropped` — semantically a record was ignored/skipped, not "dropped" (which implies it arrived successfully and was then discarded)
- `int[]` totals array is created in `flushAll` and passed through `flushViaFullScan`/`flushViaDirtyKeys` to `flushKey` — all three method signatures require updating
- `onKeyFlushed` fires *before* `store.delete` so that if a listener inspects the store, the entry is still present; the javadoc on the interface makes this guarantee explicit
- `onFlushStarted` receives `needsRecoveryScan` *before* it's reset to `false`

**Implementation steps**:

1. Add `import com.snekse.kafka.streams.resequence.listener.ResequenceEventListener;` to `ResequenceProcessor.java`
2. Add `private final ResequenceEventListener listener;` field
3. Update existing 3-arg constructor to chain to the new 6-arg constructor with `ResequenceEventListener.noOp()`
4. Update existing 5-arg constructor to chain to the new 6-arg constructor with `ResequenceEventListener.noOp()`
5. Add new 6-arg constructor with null-coalescing for `listener`
6. In `process()`: add `listener.onRecordIgnored()` before the null-key `return`; after `store.put`/`dirtyKeys.add`, add `onTombstoneReceived` (guarded by `value == null`) then `onRecordBuffered`
7. In `flushAll()`: add `listener.onFlushStarted(needsRecoveryScan)` before the branch; add `int[] totals = {0, 0}`; update calls to `flushViaFullScan` and `flushViaDirtyKeys` to pass `totals`; add `listener.onFlushCompleted(totals[0], totals[1])` after `dirtyKeys.clear()`
8. Update `flushViaFullScan(long timestamp)` → `flushViaFullScan(long timestamp, int[] totals)`; thread `totals` to `flushKey`
9. Update `flushViaDirtyKeys(long timestamp)` → `flushViaDirtyKeys(long timestamp, int[] totals)`; thread `totals` to `flushKey`
10. Update `flushKey(K key, ..., long timestamp)` → add `int[] totals` param; move `listener.onKeyFlushed` call to before `store.delete`; add `totals[0]++` and `totals[1] += records.size()` after delete
11. Run tests: `./gradlew :resequence-starter:test`

**Feedback loop**:
- **Playground**: Existing `ResequenceProcessorSpec` — run it to verify no regressions
- **Experiment**: All existing test cases pass with the no-op listener; new spec adds mock-listener cases
- **Check command**: `./gradlew :resequence-starter:test`

---

### ResequenceEventListenerSpec

**Pattern to follow**: `resequence-starter/src/test/groovy/com/snekse/kafka/streams/resequence/processor/ResequenceProcessorSpec.groovy` (TopologyTestDriver setup, test record pattern)

**Overview**: Verifies that each listener callback fires at the right time with the right arguments. Uses a Spock `Mock(ResequenceEventListener)` so interaction counts and argument values can be asserted with `_` wildcards and specific matchers.

```groovy
package com.snekse.kafka.streams.resequence.listener

import com.snekse.kafka.streams.resequence.domain.BufferedRecord
import com.snekse.kafka.streams.resequence.domain.ResequenceComparator
import com.snekse.kafka.streams.resequence.domain.TombstoneSortOrder
import com.snekse.kafka.streams.resequence.processor.ResequenceProcessor
import com.snekse.kafka.streams.resequence.serde.BufferedRecordListSerde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.state.Stores
import org.springframework.kafka.support.serializer.JacksonJsonSerde
import spock.lang.AutoCleanup
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

import java.time.Duration

class ResequenceEventListenerSpec extends Specification {

    static final String SOURCE_TOPIC = 'input'
    static final String SINK_TOPIC = 'output'
    static final String STATE_STORE = 'test-store'
    static final Duration FLUSH_INTERVAL = Duration.ofMillis(100)

    @AutoCleanup
    TopologyTestDriver driver

    ResequenceEventListener listener = Mock()

    // Build driver with the mock listener injected
    private TopologyTestDriver buildDriver(ResequenceEventListener l) {
        // ... same topology wiring as ResequenceProcessorSpec but using 6-arg constructor
    }
}
```

**Key test cases**:

- `onNullKeyDropped` fires once when a null-key record is sent; no buffering, no output
- `onRecordBuffered` fires once per valid record, with the correct key and a non-null `BufferedRecord`
- `onFlushStarted(true)` fires on the first flush after driver creation (recovery scan)
- `onFlushStarted(false)` fires on subsequent flushes
- `onKeyFlushed` fires once per distinct key in a flush, with correct record count
- `onFlushCompleted` fires once per flush with correct `keysCount` and `recordsCount` totals
- `noOp()` listener: existing processor behavior unchanged, all tests pass with no mocking

**Implementation steps**:

1. Create `ResequenceEventListenerSpec.groovy` in the `listener` package
2. Wire topology using the same `TopologyTestDriver` pattern from `ResequenceProcessorSpec` — copy the setup helper, swap in the 6-arg `ResequenceProcessor` constructor with `listener`
3. Write one `when/then` block per callback
4. Run: `./gradlew :resequence-starter:test`

**Feedback loop**:
- **Playground**: `./gradlew :resequence-starter:test --tests "*.ResequenceEventListenerSpec"`
- **Experiment**: Add one record, advance wall clock past flush interval, assert interaction counts
- **Check command**: `./gradlew :resequence-starter:test --tests "*.ResequenceEventListenerSpec"`

## Testing Requirements

### Unit Tests

| Test File | Coverage |
| --- | --- |
| `resequence-starter/src/test/groovy/.../listener/ResequenceEventListenerSpec.groovy` | All 6 listener callbacks; tombstone vs normal record distinction; ignored record path; first-flush vs subsequent-flush; totals accuracy |

**Key test cases**:
- Send 1 null-key record → `onRecordIgnored` called 1 time; `onRecordBuffered` never called; no output
- Send 1 null-value record (tombstone) → `onTombstoneReceived` called 1 time AND `onRecordBuffered` called 1 time (both fire)
- Send 1 non-null record → `onRecordBuffered` called 1 time; `onTombstoneReceived` never called
- Send 3 valid records under 2 keys, advance clock → `onFlushCompleted(2, 3)` on first flush
- Send 2 records for same key → `onKeyFlushed(key, 2)` on flush
- Advance clock twice → `onFlushStarted(true)` on first flush, `onFlushStarted(false)` on second
- Use `noOp()` listener → existing processor tests unaffected

### Manual Testing

- [ ] Run `./gradlew :resequence-starter:test` — all tests pass including existing `ResequenceProcessorSpec`

## Error Handling

| Error Scenario | Handling Strategy |
| --- | --- |
| Listener throws exception in callback | Let it propagate — same contract as KStreams processor exceptions; don't silently swallow |
| `null` listener passed to 6-arg constructor | Coerce to `noOp()` (matches existing null-coalescing pattern for `valueMapper`) |

## Validation Commands

```bash
# Build and test the starter module only
./gradlew :resequence-starter:test

# Verify no regressions in the full build
./gradlew clean build
```

---

_This spec is ready for implementation. Follow the patterns and validate at each step._
