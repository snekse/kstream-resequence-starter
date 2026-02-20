package com.example.sampleapp.processor;

import com.example.sampleapp.domain.BufferedRecord;

/**
 * Functional interface for transforming a buffered record's value before forwarding.
 *
 * <p>Implementations receive both the mapped output key ({@code KR}) and the full
 * {@link BufferedRecord} (which carries the original value plus Kafka metadata such as
 * partition, offset, and timestamp). This allows enrichment strategies that depend on
 * either the output key or the underlying Kafka metadata.
 *
 * <p>Use {@link #noOp()} to obtain a pass-through implementation that forwards the
 * original record value unchanged when no transformation is needed.
 *
 * @param <KR> the output key type (after optional re-keying via {@link KeyMapper})
 * @param <V>  the input value type
 * @param <VR> the output value type
 */
@FunctionalInterface
public interface ValueMapper<KR, V, VR> {

    /**
     * Maps a buffered record to an output value.
     *
     * @param outputKey the mapped output key for this record
     * @param bufferedRecord the buffered record containing the value and Kafka metadata
     * @return the mapped output value (may be {@code null} to forward a tombstone)
     */
    VR mapValue(KR outputKey, BufferedRecord<V> bufferedRecord);

    /**
     * Returns a no-op {@code ValueMapper} that forwards the original record value unchanged.
     *
     * @param <KR> the output key type
     * @param <V>  the value type
     * @return a pass-through {@code ValueMapper}
     */
    static <KR, V> ValueMapper<KR, V, V> noOp() {
        return (outputKey, bufferedRecord) -> bufferedRecord.getRecord();
    }
}
