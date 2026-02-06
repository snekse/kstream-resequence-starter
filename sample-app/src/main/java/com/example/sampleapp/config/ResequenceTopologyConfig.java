package com.example.sampleapp.config;

import com.example.sampleapp.domain.SampleRecord;
import com.example.sampleapp.processor.ResequenceProcessor;
import com.example.sampleapp.serde.SampleRecordListSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.support.serializer.JacksonJsonSerde;

import java.util.List;

@Configuration
@EnableKafkaStreams
public class ResequenceTopologyConfig {

    @Bean
    public Serde<SampleRecord> sampleRecordSerde() {
        return new JacksonJsonSerde<>(SampleRecord.class);
    }

    @Bean
    public Serde<List<SampleRecord>> sampleRecordListSerde() {
        return new SampleRecordListSerde();
    }

    @Bean
    public Topology resequencingTopology(
            @Value("${app.pipeline.source.topic}") String sourceTopic,
            @Value("${app.pipeline.sink.topic}") String sinkTopic,
            StreamsBuilder builder,
            Serde<SampleRecord> sampleRecordSerde,
            Serde<List<SampleRecord>> sampleRecordListSerde) {

        Topology topology = builder.build();

        // Add state store
        topology.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("resequence-buffer"),
                Serdes.Long(),
                sampleRecordListSerde));

        // Add source
        topology.addSource("source",
                Serdes.Long().deserializer(),
                sampleRecordSerde.deserializer(),
                sourceTopic);

        // Add processor
        topology.addProcessor("resequencer",
                () -> new ResequenceProcessor(sinkTopic),
                "source");

        // Connect state store to processor
        topology.connectProcessorAndStateStores("resequencer", "resequence-buffer");

        // Add sink
        topology.addSink("sink",
                sinkTopic,
                Serdes.String().serializer(),
                sampleRecordSerde.serializer(),
                "resequencer");

        return topology;
    }
}
