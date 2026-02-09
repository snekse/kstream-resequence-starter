package com.example.sampleapp.config;

import com.example.sampleapp.domain.SampleRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Profile("!test")
public class EmbeddedKafkaConfig {

    @Bean
    public EmbeddedKafkaBroker embeddedKafkaBroker(
            @Value("${app.pipeline.source.topic}") String topic) {
        return new EmbeddedKafkaKraftBroker(1, 3, topic);
    }

    @SuppressWarnings("unchecked")
    @Bean
    public ProducerFactory<Object, Object> producerFactory(
            EmbeddedKafkaBroker broker,
            KafkaProperties properties,
            Serde<SampleRecord> sampleRecordSerde) {
        Map<String, Object> props = properties.buildProducerProperties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        return new DefaultKafkaProducerFactory(props, null, sampleRecordSerde.serializer());
    }

    @SuppressWarnings("unchecked")
    @Bean
    public ConsumerFactory<Object, Object> consumerFactory(
            EmbeddedKafkaBroker broker,
            KafkaProperties properties,
            Serde<SampleRecord> sampleRecordSerde) {
        Map<String, Object> props = properties.buildConsumerProperties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        return new DefaultKafkaConsumerFactory(props, null, sampleRecordSerde.deserializer());
    }

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kStreamsConfigs(KafkaProperties kafkaProperties, EmbeddedKafkaBroker broker) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildStreamsProperties());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        return new KafkaStreamsConfiguration(props);
    }
}
