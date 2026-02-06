package com.example.sampleapp;

import com.example.sampleapp.domain.SampleRecord;
import com.example.sampleapp.processor.ResequenceProcessor;
import com.example.sampleapp.producer.SampleProducer;
import com.example.sampleapp.serde.SampleRecordListSerde;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerde;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.HashMap;
import java.util.Map;

@EnableKafkaStreams
@SpringBootApplication
public class SampleApplication {

        public static void main(String[] args) {
                SpringApplication.run(SampleApplication.class, args);
        }

        @Bean
        @Profile("!test")
        public CommandLineRunner runner(SampleProducer producer) {
                return args -> producer.produceSampleData();
        }

        @Bean
        public Topology resequencingTopology(
                        @Value("${app.pipeline.source.topic}") String sourceTopic,
                        @Value("${app.pipeline.sink.topic}") String sinkTopic,
                        StreamsBuilder builder) {

                // Build topology using low-level API for full control
                Topology topology = builder.build();

                // Add state store
                topology.addStateStore(Stores.keyValueStoreBuilder(
                                Stores.persistentKeyValueStore("resequence-buffer"),
                                Serdes.Long(),
                                new SampleRecordListSerde()));

                var sampleRecordSerde = new JacksonJsonSerde<>(SampleRecord.class);

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

        @Bean
        @Profile("!test")
        public EmbeddedKafkaBroker embeddedKafkaBroker(
                        @Value("${app.pipeline.source.topic}") String topic) {
                return new EmbeddedKafkaKraftBroker(1, 3, topic);
        }

        @Bean
        @Profile("!test")
        public ProducerFactory<Object, Object> producerFactory(EmbeddedKafkaBroker broker, KafkaProperties properties) {
                Map<String, Object> props = properties.buildProducerProperties();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
                return new DefaultKafkaProducerFactory<>(props);
        }

        @Bean
        @Profile("!test")
        public ConsumerFactory<Object, Object> consumerFactory(EmbeddedKafkaBroker broker, KafkaProperties properties) {
                Map<String, Object> props = properties.buildConsumerProperties();
                props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
                return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
        @Profile("!test")
        public KafkaStreamsConfiguration kStreamsConfigs(KafkaProperties kafkaProperties, EmbeddedKafkaBroker broker) {
                Map<String, Object> props = new HashMap<>(kafkaProperties.buildStreamsProperties());
                props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
                return new KafkaStreamsConfiguration(props);
        }
}
