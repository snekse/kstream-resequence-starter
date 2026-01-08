package com.example.sampleapp;

import com.example.sampleapp.producer.SampleProducer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.Map;

@SpringBootApplication
public class SampleApplication {

        public static void main(String[] args) {
                SpringApplication.run(SampleApplication.class, args);
        }

        @Bean
        public CommandLineRunner runner(SampleProducer producer) {
                return args -> producer.produceSampleData();
        }

        @Bean
        @Profile("!test")
        public EmbeddedKafkaBroker embeddedKafkaBroker(
                        @Value("${app.topic.name:sample-topic}") String topic) {
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
}
