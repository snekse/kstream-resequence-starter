package com.example.sampleapp.config;

import com.example.sampleapp.domain.SampleRecord;
import com.example.sampleapp.domain.SampleRecordComparator;
import com.snekse.kafka.streams.resequence.Resequencer;
import com.snekse.kafka.streams.resequence.domain.ResequenceComparator;
import com.snekse.kafka.streams.resequence.spring.ResequenceProperties;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.support.serializer.JacksonJsonSerde;

@Configuration
@EnableKafkaStreams
public class ResequenceTopologyConfig {

    @Bean
    public Serde<SampleRecord> sampleRecordSerde() {
        return new JacksonJsonSerde<>(SampleRecord.class);
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
            Serde<SampleRecord> sampleRecordSerde,
            ResequenceComparator<SampleRecord> resequenceComparator,
            StreamsBuilder builder) {

        // Create a fresh builder rather than injecting the auto-configured Builder<?, ?, ?, ?>
        // because keyMapper() and valueMapper() change the builder's type parameters, which is
        // incompatible with wildcard injection. Properties are applied manually instead.
        var resequencer = Resequencer.<Long, SampleRecord>builder()
                .comparator(resequenceComparator)
                .valueSerde(sampleRecordSerde)
                .keySerde(Serdes.Long())
                .stateStoreName(resequenceProperties.getStateStoreName())
                .flushInterval(resequenceProperties.getFlushInterval())
                .keyMapper(key -> key + "-sorted")
                .valueMapper((outputKey, buffered) -> {
                    SampleRecord record = buffered.record();
                    if (record != null) {
                        record.setNewKey(outputKey);
                    }
                    return record;
                })
                .build();

        Topology topology = builder.build();

        // Add source
        topology.addSource("source",
                Serdes.Long().deserializer(),
                sampleRecordSerde.deserializer(),
                sourceTopic);

        // Add resequencer (processor + state store)
        resequencer.addTo(topology, "resequencer", "source");

        // Add sink
        topology.addSink("sink",
                sinkTopic,
                Serdes.String().serializer(),
                sampleRecordSerde.serializer(),
                "resequencer");

        return topology;
    }
}
