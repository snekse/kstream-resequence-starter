package com.snekse.kafka.streams.resequence.processor;

import com.snekse.kafka.streams.resequence.domain.BufferedRecord;
import com.snekse.kafka.streams.resequence.domain.ResequenceComparator;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.ContextualProcessor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kafka Streams processor that buffers incoming records and re-emits them in sorted order on a
 * wall-clock punctuator schedule.
 *
 * <h2>Flushing flow</h2>
 * <ol>
 *   <li>Each call to {@link #process} appends the incoming record to the state store entry for its
 *       key and registers the key in an in-memory {@code dirtyKeys} set.</li>
 *   <li>On every flush interval, {@link #flushAll} is invoked by the punctuator.
 *       <ul>
 *         <li><strong>First flush after startup or rebalance</strong> — {@code needsRecoveryScan}
 *             is {@code true}, so {@link #flushViaFullScan} iterates the entire state store via
 *             {@code store.all()}. This recovers any records that were persisted before this
 *             processor instance was created (e.g. from a previous task assignment). After the scan
 *             completes, {@code needsRecoveryScan} is set to {@code false}.</li>
 *         <li><strong>Subsequent flushes</strong> — {@link #flushViaDirtyKeys} performs a point
 *             lookup ({@code store.get(key)}) for each key in {@code dirtyKeys}. Only keys that
 *             received at least one record since the last flush are visited, making the flush cost
 *             O(D) where D is the number of distinct dirty keys rather than O(N) total store
 *             entries.</li>
 *       </ul>
 *   </li>
 *   <li>Both paths delegate to {@link #flushKey}, which sorts the buffered records using the
 *       injected {@link ResequenceComparator}, applies the key and value mappers, forwards each
 *       record downstream, and deletes the state store entry for that key.</li>
 *   <li>{@code dirtyKeys} is cleared at the end of every flush regardless of which path was
 *       taken.</li>
 * </ol>
 *
 * @param <K>  input key type
 * @param <V>  input value type
 * @param <KR> output key type (may differ from K when a {@link KeyMapper} is provided)
 * @param <VR> output value type (may differ from V when a {@link ValueMapper} is provided)
 */
public class ResequenceProcessor<K, V, KR, VR> extends ContextualProcessor<K, V, KR, VR> {

    private final ResequenceComparator<V> comparator;
    private final String stateStoreName;
    private final Duration flushInterval;
    private final KeyMapper<K, KR> keyMapper;
    private final ValueMapper<KR, V, VR> valueMapper;
    private final Set<K> dirtyKeys = new HashSet<>();
    private boolean needsRecoveryScan = true;
    private KeyValueStore<K, List<BufferedRecord<V>>> store;

    @SuppressWarnings("unchecked")
    public ResequenceProcessor(ResequenceComparator<V> comparator, String stateStoreName, Duration flushInterval) {
        this(comparator, stateStoreName, flushInterval, null, (ValueMapper<KR, V, VR>) ValueMapper.noOp());
    }

    @SuppressWarnings("unchecked")
    public ResequenceProcessor(ResequenceComparator<V> comparator, String stateStoreName, Duration flushInterval,
                               KeyMapper<K, KR> keyMapper, ValueMapper<KR, V, VR> valueMapper) {
        this.comparator = comparator;
        this.stateStoreName = stateStoreName;
        this.flushInterval = flushInterval;
        this.keyMapper = keyMapper;
        this.valueMapper = valueMapper != null ? valueMapper : (ValueMapper<KR, V, VR>) ValueMapper.noOp();
    }

    @Override
    public void init(ProcessorContext<KR, VR> context) {
        super.init(context);
        this.store = context.getStateStore(stateStoreName);

        // Schedule punctuator to flush buffered records at configured interval (wall-clock time)
        context.schedule(flushInterval, PunctuationType.WALL_CLOCK_TIME, this::flushAll);
    }

    @Override
    public void process(Record<K, V> record) {
        K key = record.key();
        V value = record.value();

        // Skip records with null keys to avoid NPE in state store operations
        if (key == null) {
            return;
        }

        // Wrap with Kafka metadata for proper ordering
        BufferedRecord<V> buffered = BufferedRecord.<V>builder()
                .record(value)
                .partition(context().recordMetadata().map(RecordMetadata::partition).orElse(-1))
                .offset(context().recordMetadata().map(RecordMetadata::offset).orElse(-1L))
                .timestamp(record.timestamp())
                .build();

        // Get or create list for this key
        List<BufferedRecord<V>> records = store.get(key);
        if (records == null) {
            records = new ArrayList<>();
        }
        records.add(buffered);
        store.put(key, records);
        dirtyKeys.add(key);
    }

    /**
     * Punctuator callback invoked every flush interval. Routes to the recovery scan on the first
     * call after startup or rebalance, then switches permanently to the dirty-key path.
     * Clears {@code dirtyKeys} after either path completes.
     */
    private void flushAll(long timestamp) {
        if (needsRecoveryScan) {
            flushViaFullScan(timestamp);
            needsRecoveryScan = false;
        } else {
            flushViaDirtyKeys(timestamp);
        }
        dirtyKeys.clear();
    }

    /**
     * Scans the entire state store and flushes every key. Used once per task assignment to recover
     * records that were persisted before this processor instance was created.
     */
    private void flushViaFullScan(long timestamp) {
        try (KeyValueIterator<K, List<BufferedRecord<V>>> iter = store.all()) {
            while (iter.hasNext()) {
                var entry = iter.next();
                flushKey(entry.key, entry.value, timestamp);
            }
        }
    }

    /**
     * Flushes only the keys that received at least one record since the last flush. Uses point
     * lookups ({@code store.get}) rather than a full scan, keeping flush cost proportional to
     * write activity rather than total store size.
     */
    private void flushViaDirtyKeys(long timestamp) {
        for (K key : dirtyKeys) {
            List<BufferedRecord<V>> records = store.get(key);
            flushKey(key, records, timestamp);
        }
    }

    /**
     * Sorts, maps, and forwards all buffered records for a single key, then deletes the state store
     * entry. No-ops if {@code records} is null or empty. Called by both flush paths.
     */
    @SuppressWarnings("unchecked")
    private void flushKey(K key, List<BufferedRecord<V>> records, long timestamp) {
        if (records == null || records.isEmpty()) {
            return;
        }

        // Sort using the injected comparator
        records.sort(comparator);

        // Map the key using the provided key mapper, or pass through unchanged
        KR outputKey = keyMapper != null ? keyMapper.map(key) : (KR) key;

        // Forward each record, applying the value mapper (noOp by default)
        for (BufferedRecord<V> br : records) {
            VR mappedValue = valueMapper.mapValue(outputKey, br);
            context().forward(new Record<>(outputKey, mappedValue, timestamp));
        }

        // Clear the buffer for this key
        store.delete(key);
    }
}
