package com.example.sampleapp.config;

import com.example.sampleapp.domain.SampleRecordComparator;
import com.example.sampleapp.domain.SampleRecord;
import com.snekse.kafka.streams.resequence.config.ResequenceProperties;
import com.snekse.kafka.streams.resequence.domain.BufferedRecord;
import com.snekse.kafka.streams.resequence.domain.ResequenceComparator;
import com.snekse.kafka.streams.resequence.processor.KeyMapper;
import com.snekse.kafka.streams.resequence.processor.ResequenceProcessor;
import com.snekse.kafka.streams.resequence.processor.ValueMapper;
import com.snekse.kafka.streams.resequence.serde.BufferedRecordListSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.support.serializer.JacksonJsonSerde;

import java.util.List;
import java.util.Objects;

@Configuration
@EnableKafkaStreams
public class ResequenceTopologyConfig {

    @Bean
    public Serde<SampleRecord> sampleRecordSerde() {
        return new JacksonJsonSerde<>(SampleRecord.class);
    }

    @Bean
    public Serde<List<BufferedRecord<SampleRecord>>> bufferedRecordListSerde(JsonMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        return new BufferedRecordListSerde<>(SampleRecord.class, objectMapper);
    }

    @Bean
    public ResequenceComparator<SampleRecord> resequenceComparator(ResequenceProperties properties) {
        return new SampleRecordComparator(properties.getTombstoneSortOrder());
    }

    @Bean
    public Topology resequencingTopology(
            @Value("${app.pipeline.source.topic}") String sourceTopic,
            @Value("${app.pipeline.sink.topic}") String sinkTopic,
            ResequenceProperties resequenceProperties,
            StreamsBuilder builder,
            Serde<SampleRecord> sampleRecordSerde,
            Serde<List<BufferedRecord<SampleRecord>>> bufferedRecordListSerde,
            ResequenceComparator<SampleRecord> resequenceComparator) {

        String stateStoreName = resequenceProperties.getStateStoreName();
        Topology topology = builder.build();

        // Add state store
        topology.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(stateStoreName),
                Serdes.Long(),
                bufferedRecordListSerde));

        // Add source
        topology.addSource("source",
                Serdes.Long().deserializer(),
                sampleRecordSerde.deserializer(),
                sourceTopic);

        // Re-key from Long to String with "-sorted" suffix
        KeyMapper<Long, String> keyMapper = key -> key + "-sorted";

        // Enrich each record with the mapped output key so downstream consumers can read it from the value
        ValueMapper<String, SampleRecord, SampleRecord> valueMapper = (outputKey, buffered) -> {
            SampleRecord record = buffered.getRecord();
            if (record != null) {
                record.setNewKey(outputKey);
            }
            return record;
        };

        // Add processor with injected comparator, state store name, and flush interval
        topology.addProcessor("resequencer",
                () -> new ResequenceProcessor<>(resequenceComparator, stateStoreName, resequenceProperties.getFlushInterval(), keyMapper, valueMapper),
                "source");

        // Connect state store to processor
        topology.connectProcessorAndStateStores("resequencer", stateStoreName);

        // Add sink
        topology.addSink("sink",
                sinkTopic,
                Serdes.String().serializer(),
                sampleRecordSerde.serializer(),
                "resequencer");

        return topology;
    }
}
