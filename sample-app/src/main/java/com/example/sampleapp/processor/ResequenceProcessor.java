package com.example.sampleapp.processor;

import com.example.sampleapp.domain.BufferedRecord;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.ContextualProcessor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ResequenceProcessor<K, V, KR, VR> extends ContextualProcessor<K, V, KR, VR> {

    private final Comparator<BufferedRecord<V>> comparator;
    private final String stateStoreName;
    private final Duration flushInterval;
    private final KeyMapper<K, KR> keyMapper;
    private final ValueMapper<KR, V, VR> valueMapper;
    private KeyValueStore<K, List<BufferedRecord<V>>> store;

    public ResequenceProcessor(Comparator<BufferedRecord<V>> comparator, String stateStoreName, Duration flushInterval) {
        this(comparator, stateStoreName, flushInterval, null, ValueMapper.noOp());
    }

    public ResequenceProcessor(Comparator<BufferedRecord<V>> comparator, String stateStoreName, Duration flushInterval,
                               KeyMapper<K, KR> keyMapper, ValueMapper<KR, V, VR> valueMapper) {
        this.comparator = comparator;
        this.stateStoreName = stateStoreName;
        this.flushInterval = flushInterval;
        this.keyMapper = keyMapper;
        this.valueMapper = valueMapper != null ? valueMapper : ValueMapper.noOp();
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
    }

    @SuppressWarnings("unchecked")
    private void flushAll(long timestamp) {
        try (KeyValueIterator<K, List<BufferedRecord<V>>> iter = store.all()) {
            while (iter.hasNext()) {
                var entry = iter.next();
                K key = entry.key;
                List<BufferedRecord<V>> records = entry.value;

                if (records != null && !records.isEmpty()) {
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
        }
    }
}
