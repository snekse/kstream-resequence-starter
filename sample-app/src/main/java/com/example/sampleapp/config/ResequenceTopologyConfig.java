package com.example.sampleapp.config;

import com.example.sampleapp.domain.BufferedRecord;
import com.example.sampleapp.domain.ResequenceComparator;
import com.example.sampleapp.domain.SampleRecord;
import com.example.sampleapp.processor.KeyMapper;
import com.example.sampleapp.processor.ResequenceProcessor;
import com.example.sampleapp.processor.ValueMapper;
import com.example.sampleapp.serde.BufferedRecordListSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.support.serializer.JacksonJsonSerde;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Configuration
@EnableKafkaStreams
@EnableConfigurationProperties(ResequenceProperties.class)
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
    public Comparator<BufferedRecord<SampleRecord>> resequenceComparator(ResequenceProperties properties) {
        return new ResequenceComparator(properties.getTombstoneSortOrder());
    }

    @Bean
    public Topology resequencingTopology(
            @Value("${app.pipeline.source.topic}") String sourceTopic,
            @Value("${app.pipeline.sink.topic}") String sinkTopic,
            ResequenceProperties resequenceProperties,
            StreamsBuilder builder,
            Serde<SampleRecord> sampleRecordSerde,
            Serde<List<BufferedRecord<SampleRecord>>> bufferedRecordListSerde,
            Comparator<BufferedRecord<SampleRecord>> resequenceComparator) {

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

        // Enrich each record using Kafka metadata exposed by the ValueMapper
        ValueMapper<SampleRecord, SampleRecord> valueMapper = buffered -> {
            SampleRecord record = buffered.getRecord();
            if (record != null) {
                record.setNewKey("partition-" + buffered.getPartition() + "-offset-" + buffered.getOffset());
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
