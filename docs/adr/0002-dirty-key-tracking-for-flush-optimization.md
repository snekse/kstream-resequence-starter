# ADR 0002: Dirty-Key Tracking for Flush Optimization

## Status

Accepted

## Context

The `ResequenceProcessor.flushAll()` method calls `store.all()` every flush interval (default 2 seconds), performing a full scan of the state store. This is O(N) where N is the total number of entries, including RocksDB tombstones awaiting compaction.

Since the store is cleared after each flush, only keys that received new records since the last flush need processing. Under high key cardinality or short flush intervals, the full scan is wasteful and can block the Kafka Streams thread.

## Decision

Track which keys have been written since the last flush using an in-memory `HashSet<K>` (`dirtyKeys`). On flush, iterate only those keys with point lookups (`store.get(key)`) instead of scanning via `store.all()`.

On the first flush after startup or rebalance, perform one full `store.all()` scan to recover any data persisted before the processor instance was created. The in-memory set does not survive rebalances, but the RocksDB state store does.

### Implementation

Two new fields on `ResequenceProcessor`:
- `Set<K> dirtyKeys` — keys written since the last flush
- `boolean needsRecoveryScan` — starts `true`, set to `false` after first successful full scan

The `flushAll` method routes to either `flushViaFullScan` (recovery path) or `flushViaDirtyKeys` (steady-state path). Both delegate to a shared `flushKey` method for sort/forward/delete logic.

### Cluster behavior

Each Kafka Streams task has its own processor instance and state store partition. On rebalance, the old instance is discarded (losing its in-memory `dirtyKeys`), and a new instance starts with `needsRecoveryScan = true`. The recovery scan is scoped to the task's partition data, so it only reads entries relevant to that task.

## Rejected Alternative: Periodic Full Scan

Considered adding a `fullScanEveryNFlushes` configuration option as a safety net. Rejected because dirty-key tracking is exact — every `store.put` is paired with `dirtyKeys.add`. Periodic full scans would mask bugs rather than surface them, and would add configuration complexity with no correctness benefit.

## Consequences

- **Flush cost drops from O(N) to O(D)** where D is the number of dirty keys since the last flush, which is typically much smaller than N.
- **No API changes** — constructor signatures, `ResequenceProperties`, and topology wiring are unchanged.
- **One-time recovery cost** per task assignment — the first flush after startup or rebalance still performs a full scan.
- **Negligible memory overhead** — the `dirtyKeys` set holds at most the number of distinct keys received between two flush intervals.
