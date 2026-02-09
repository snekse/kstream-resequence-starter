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

    private final Comparator<BufferedRecord> comparator;
    private KeyValueStore<Long, List<BufferedRecord>> store;

    public ResequenceProcessor(Comparator<BufferedRecord> comparator) {
        this.comparator = comparator;
    }

    @Override
    public void init(ProcessorContext<String, SampleRecord> context) {
        super.init(context);
        // TODO: Make state store name configurable in application yml
        this.store = context.getStateStore("resequence-buffer");

        // Schedule punctuator to flush buffered records every 2 seconds (wall-clock time)
        // TODO: Make duration configurable in application yml
        context.schedule(Duration.ofSeconds(2), PunctuationType.WALL_CLOCK_TIME, this::flushAll);
    }

    @Override
    public void process(Record<Long, SampleRecord> record) {
        Long key = record.key();
        SampleRecord value = record.value();

        // Wrap with Kafka metadata for proper ordering
        BufferedRecord buffered = BufferedRecord.builder()
                .record(value)
                .partition(context().recordMetadata().map(RecordMetadata::partition).orElse(-1))
                .offset(context().recordMetadata().map(RecordMetadata::offset).orElse(-1L))
                .timestamp(record.timestamp())
                .build();

        // Get or create list for this key
        List<BufferedRecord> records = store.get(key);
        if (records == null) {
            records = new ArrayList<>();
        }
        records.add(buffered);
        store.put(key, records);
    }

    private void flushAll(long timestamp) {
        try (KeyValueIterator<Long, List<BufferedRecord>> iter = store.all()) {
            while (iter.hasNext()) {
                var entry = iter.next();
                Long key = entry.key;
                List<BufferedRecord> records = entry.value;

                if (records != null && !records.isEmpty()) {
                    // Sort using the injected comparator
                    records.sort(comparator);

                    // TODO: Re-keying should be optional and if wanted, a mapping function should be provided
                    // TODO: Should support non-String keys. When doing so,
                    //       we might need to switch to needing a mapping service provided.
                    // Forward each record
                    String newKey = key + "-sorted";
                    for (BufferedRecord br : records) {
                        // TODO: An optional Value mapper should be providable the type can be mapped.
                        SampleRecord r = br.getRecord();
                        r.setNewKey(newKey);
                        context().forward(new Record<>(newKey, r, timestamp));
                    }

                    // Clear the buffer for this key
                    store.delete(key);
                }
            }
        }
    }
}
