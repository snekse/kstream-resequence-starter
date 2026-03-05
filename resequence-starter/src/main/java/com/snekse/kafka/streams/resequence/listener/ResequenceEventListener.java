package com.snekse.kafka.streams.resequence.listener;

import com.snekse.kafka.streams.resequence.domain.BufferedRecord;

/**
 * Callback interface for observing significant events in a
 * {@link com.snekse.kafka.streams.resequence.processor.ResequenceProcessor}.
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
     * It is not called for records with null keys (see {@link #onRecordIgnored()}).
     * For tombstone records (null value), this callback fires in addition to
     * {@link #onTombstoneReceived(Object, BufferedRecord)}.
     *
     * @param key    the record's input key (before any key mapping)
     * @param record the buffered record, including the original value and Kafka metadata
     *               (partition, offset, timestamp)
     */
    void onRecordBuffered(Object key, BufferedRecord<?> record);

    /**
     * Called when an incoming record has a null value, indicating a tombstone.
     *
     * <p>This callback fires in addition to {@link #onRecordBuffered(Object, BufferedRecord)}
     * when the record value is null. It fires before {@code onRecordBuffered} for the same
     * record. Tombstones are valid domain events — they are buffered and forwarded downstream
     * in sorted order according to the processor's
     * {@link com.snekse.kafka.streams.resequence.domain.TombstoneSortOrder} configuration.
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
