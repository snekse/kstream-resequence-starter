package com.example.sampleapp.config;

import com.example.sampleapp.domain.BufferedRecord;
import com.example.sampleapp.domain.ResequenceComparator;
import com.example.sampleapp.domain.SampleRecord;
import com.example.sampleapp.processor.ResequenceProcessor;
import com.example.sampleapp.serde.BufferedRecordListSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.support.serializer.JacksonJsonSerde;

import java.util.Comparator;
import java.util.List;

@Configuration
@EnableKafkaStreams
@EnableConfigurationProperties(ResequenceProperties.class)
public class ResequenceTopologyConfig {

    @Bean
    public Serde<SampleRecord> sampleRecordSerde() {
        return new JacksonJsonSerde<>(SampleRecord.class);
    }

    @Bean
    public Serde<List<BufferedRecord<SampleRecord>>> bufferedRecordListSerde() {
        return new BufferedRecordListSerde<>(SampleRecord.class);
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

        // Add processor with injected comparator, state store name, and flush interval
        topology.addProcessor("resequencer",
                () -> new ResequenceProcessor(resequenceComparator, stateStoreName, resequenceProperties.getFlushInterval()),
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
