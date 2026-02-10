package com.example.sampleapp.processor;

import com.example.sampleapp.domain.BufferedRecord;
import com.example.sampleapp.domain.SampleRecord;
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

public class ResequenceProcessor extends ContextualProcessor<Long, SampleRecord, String, SampleRecord> {

    private final Comparator<BufferedRecord<SampleRecord>> comparator;
    private final String stateStoreName;
    private KeyValueStore<Long, List<BufferedRecord<SampleRecord>>> store;

    public ResequenceProcessor(Comparator<BufferedRecord<SampleRecord>> comparator, String stateStoreName) {
        this.comparator = comparator;
        this.stateStoreName = stateStoreName;
    }

    @Override
    public void init(ProcessorContext<String, SampleRecord> context) {
        super.init(context);
        this.store = context.getStateStore(stateStoreName);

        // Schedule punctuator to flush buffered records every 2 secs (wall-clock time)
        // TODO: Make duration configurable in application yml
        context.schedule(Duration.ofSeconds(2), PunctuationType.WALL_CLOCK_TIME, this::flushAll);
    }

    @Override
    public void process(Record<Long, SampleRecord> record) {
        Long key = record.key();
        SampleRecord value = record.value();

        // Skip records with null keys to avoid NPE in state store operations
        if (key == null) {
            return;
        }

        // Wrap with Kafka metadata for proper ordering
        BufferedRecord<SampleRecord> buffered = BufferedRecord.<SampleRecord>builder()
                .record(value)
                .partition(context().recordMetadata().map(RecordMetadata::partition).orElse(-1))
                .offset(context().recordMetadata().map(RecordMetadata::offset).orElse(-1L))
                .timestamp(record.timestamp())
                .build();

        // Get or create list for this key
        List<BufferedRecord<SampleRecord>> records = store.get(key);
        if (records == null) {
            records = new ArrayList<>();
        }
        records.add(buffered);
        store.put(key, records);
    }

    private void flushAll(long timestamp) {
        try (KeyValueIterator<Long, List<BufferedRecord<SampleRecord>>> iter = store.all()) {
            while (iter.hasNext()) {
                var entry = iter.next();
                Long key = entry.key;
                List<BufferedRecord<SampleRecord>> records = entry.value;

                if (records != null && !records.isEmpty()) {
                    // Sort using the injected comparator
                    records.sort(comparator);

                    // TODO: Re-keying should be optional and if wanted,
                    // a mapping function should be provided
                    // TODO: Should support non-String keys.
                    // When doing so, we might need to switch to needing a mapping service provided.
                    // Forward each record
                    String newKey = key + "-sorted";
                    for (BufferedRecord<SampleRecord> br : records) {
                        // TODO: An optional Value mapper should be providable the type can be mapped.
                        SampleRecord r = br.getRecord();
                        if (r != null) {
                            r.setNewKey(newKey);
                        }
                        context().forward(new Record<>(newKey, r, timestamp));
                    }

                    // Clear the buffer for this key
                    store.delete(key);
                }
            }
        }
    }
}
