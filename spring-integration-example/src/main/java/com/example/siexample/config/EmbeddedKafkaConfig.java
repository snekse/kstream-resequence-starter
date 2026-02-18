package com.example.siexample.config;

import com.example.sampledomain.SampleRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

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
            KafkaProperties properties) {
        Map<String, Object> props = properties.buildProducerProperties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        return new DefaultKafkaProducerFactory(props, null, new JacksonJsonSerializer<>());
    }

    @SuppressWarnings("unchecked")
    @Bean
    public ConsumerFactory<Object, Object> consumerFactory(
            EmbeddedKafkaBroker broker,
            KafkaProperties properties) {
        Map<String, Object> props = properties.buildConsumerProperties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        JacksonJsonDeserializer<SampleRecord> deserializer = new JacksonJsonDeserializer<>(SampleRecord.class);
        deserializer.addTrustedPackages("com.example.siexample.domain");
        return new DefaultKafkaConsumerFactory(props, null, deserializer);
    }

    @Bean
    public KafkaTemplate<Object, Object> kafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
