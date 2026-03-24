package com.snekse.kafka.streams.resequence;

import com.snekse.kafka.streams.resequence.domain.BufferedRecord;
import com.snekse.kafka.streams.resequence.domain.ResequenceComparator;
import com.snekse.kafka.streams.resequence.processor.KeyMapper;
import com.snekse.kafka.streams.resequence.processor.ResequenceProcessor;
import com.snekse.kafka.streams.resequence.processor.ValueMapper;
import com.snekse.kafka.streams.resequence.serde.BufferedRecordListSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.time.Duration;
import java.util.List;

/**
 * Entry point for configuring and building a resequencing processor.
 *
 * <p>Usage:
 * <pre>{@code
 * var resequencer = Resequencer.<String, MyRecord>builder()
 *     .comparator(myComparator)
 *     .valueSerde(myRecordSerde)
 *     .keySerde(Serdes.String())
 *     .build();
 *
 * topology.addProcessor("resequencer", resequencer.processorSupplier(), "source");
 * topology.addStateStore(resequencer.stateStoreBuilder(), "resequencer");
 * }</pre>
 *
 * @param <K> input key type
 * @param <V> input value type
 */
public final class Resequencer<K, V> {

    private Resequencer() {}

    public static <K, V> Builder<K, V, K, V> builder() {
        return new Builder<>();
    }

    public static final class Builder<K, V, KR, VR> {
        private ResequenceComparator<V> comparator;
        private Serde<V> valueSerde;
        private Serde<K> keySerde;
        private KeyMapper<K, KR> keyMapper;
        private ValueMapper<KR, V, VR> valueMapper;
        private Duration flushInterval = Duration.ofSeconds(2);
        private String stateStoreName = "resequence-buffer";

        private Builder() {}

        public Builder<K, V, KR, VR> comparator(ResequenceComparator<V> comparator) {
            this.comparator = comparator;
            return this;
        }

        public Builder<K, V, KR, VR> valueSerde(Serde<V> valueSerde) {
            this.valueSerde = valueSerde;
            return this;
        }

        public Builder<K, V, KR, VR> keySerde(Serde<K> keySerde) {
            this.keySerde = keySerde;
            return this;
        }

        @SuppressWarnings("unchecked")
        public <KR2> Builder<K, V, KR2, VR> keyMapper(KeyMapper<K, KR2> keyMapper) {
            Builder<K, V, KR2, VR> self = (Builder<K, V, KR2, VR>) this;
            self.keyMapper = keyMapper;
            return self;
        }

        @SuppressWarnings("unchecked")
        public <VR2> Builder<K, V, KR, VR2> valueMapper(ValueMapper<KR, V, VR2> valueMapper) {
            Builder<K, V, KR, VR2> self = (Builder<K, V, KR, VR2>) this;
            self.valueMapper = valueMapper;
            return self;
        }

        public Builder<K, V, KR, VR> flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        public Builder<K, V, KR, VR> stateStoreName(String stateStoreName) {
            this.stateStoreName = stateStoreName;
            return this;
        }

        @SuppressWarnings("unchecked")
        public Definition<K, V, KR, VR> build() {
            if (comparator == null) {
                throw new IllegalStateException("comparator is required");
            }
            if (valueSerde == null) {
                throw new IllegalStateException("valueSerde is required");
            }
            if (keySerde == null) {
                throw new IllegalStateException("keySerde is required");
            }
            KeyMapper<K, KR> resolvedKeyMapper = keyMapper != null ? keyMapper : key -> (KR) key;
            @SuppressWarnings("unchecked")
            ValueMapper<KR, V, VR> resolvedValueMapper = valueMapper != null ? valueMapper : (ValueMapper<KR, V, VR>) ValueMapper.noOp();
            return new Definition<>(comparator, valueSerde, keySerde, resolvedKeyMapper, resolvedValueMapper,
                    flushInterval, stateStoreName);
        }
    }

    public static final class Definition<K, V, KR, VR> {
        private final ResequenceComparator<V> comparator;
        private final Serde<V> valueSerde;
        private final Serde<K> keySerde;
        private final KeyMapper<K, KR> keyMapper;
        private final ValueMapper<KR, V, VR> valueMapper;
        private final Duration flushInterval;
        private final String stateStoreName;

        Definition(ResequenceComparator<V> comparator, Serde<V> valueSerde, Serde<K> keySerde,
                   KeyMapper<K, KR> keyMapper, ValueMapper<KR, V, VR> valueMapper,
                   Duration flushInterval, String stateStoreName) {
            this.comparator = comparator;
            this.valueSerde = valueSerde;
            this.keySerde = keySerde;
            this.keyMapper = keyMapper;
            this.valueMapper = valueMapper;
            this.flushInterval = flushInterval;
            this.stateStoreName = stateStoreName;
        }

        public ProcessorSupplier<K, V, KR, VR> processorSupplier() {
            return () -> ResequenceProcessor.create(comparator, stateStoreName, flushInterval, keyMapper, valueMapper);
        }

        public StoreBuilder<KeyValueStore<K, List<BufferedRecord<V>>>> stateStoreBuilder() {
            return Stores.keyValueStoreBuilder(
                    Stores.persistentKeyValueStore(stateStoreName),
                    keySerde,
                    new BufferedRecordListSerde<>(valueSerde)
            );
        }

        public String stateStoreName() {
            return stateStoreName;
        }

        /**
         * Convenience method that wires the processor and state store into the given topology.
         */
        public void addTo(Topology topology, String processorName, String... parentNames) {
            topology.addProcessor(processorName, processorSupplier(), parentNames);
            topology.addStateStore(stateStoreBuilder(), processorName);
        }
    }
}
