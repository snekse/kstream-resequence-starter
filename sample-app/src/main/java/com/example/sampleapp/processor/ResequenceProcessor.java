package com.example.sampleapp.processor;

import com.example.sampleapp.domain.SampleRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.ContextualProcessor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ResequenceProcessor extends ContextualProcessor<Long, SampleRecord, String, SampleRecord> {

    private static final Map<String, Integer> OPERATION_ORDER = Map.of(
            "CREATE", 0,
            "UPDATE", 1,
            "DELETE", 2);

    private final String sinkTopic;
    private KeyValueStore<Long, List<SampleRecord>> store;

    public ResequenceProcessor(String sinkTopic) {
        this.sinkTopic = sinkTopic;
    }

    @Override
    public void init(ProcessorContext<String, SampleRecord> context) {
        super.init(context);
        this.store = context.getStateStore("resequence-buffer");

        // Schedule punctuator to flush buffered records every 2 seconds (wall-clock time)
        context.schedule(Duration.ofSeconds(2), PunctuationType.WALL_CLOCK_TIME, this::flushAll);
    }

    @Override
    public void process(Record<Long, SampleRecord> record) {
        Long key = record.key();
        SampleRecord value = record.value();

        // Get or create list for this key
        List<SampleRecord> records = store.get(key);
        if (records == null) {
            records = new ArrayList<>();
        }
        records.add(value);
        store.put(key, records);
    }

    private void flushAll(long timestamp) {
        try (KeyValueIterator<Long, List<SampleRecord>> iter = store.all()) {
            while (iter.hasNext()) {
                var entry = iter.next();
                Long key = entry.key;
                List<SampleRecord> records = entry.value;

                if (records != null && !records.isEmpty()) {
                    // Sort by operation type
                    records.sort(Comparator.comparingInt(r ->
                            OPERATION_ORDER.getOrDefault(r.getOperationType(), Integer.MAX_VALUE)));

                    // Forward each record
                    String newKey = key + "-sorted";
                    for (SampleRecord r : records) {
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
