package com.example.sampleapp;

import com.example.sampleapp.domain.SampleRecord;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class SampleAppApplication {

        public static void main(String[] args) {
                SpringApplication.run(SampleAppApplication.class, args);
        }

        @Bean
        public CommandLineRunner runner(com.example.sampleapp.producer.SampleProducer producer) {
                return args -> producer.produceSampleData();
        }

        @Bean
        @Profile("!test")
        public EmbeddedKafkaBroker embeddedKafkaBroker() {
                return new EmbeddedKafkaKraftBroker(1, 1, "sample-topic")
                                .brokerListProperty("spring.kafka.bootstrap-servers");
        }

        @Bean
        public KafkaAdmin kafkaAdmin(EmbeddedKafkaBroker broker) {
                Map<String, Object> configs = new HashMap<>();
                configs.put(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                broker.getBrokersAsString());
                return new KafkaAdmin(configs);
        }

        @Bean
        public NewTopic sampleTopic() {
                return TopicBuilder.name("sample-topic")
                                .partitions(1)
                                .replicas(1)
                                .build();
        }

        @Bean
        public ProducerFactory<Long, SampleRecord> producerFactory(EmbeddedKafkaBroker broker) {
                Map<String, Object> configProps = new HashMap<>();
                configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
                configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class);
                configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
                return new DefaultKafkaProducerFactory<>(configProps);
        }

        @Bean
        public KafkaTemplate<Long, SampleRecord> kafkaTemplate(ProducerFactory<Long, SampleRecord> pf) {
                return new KafkaTemplate<>(pf);
        }

        @Bean
        public ConsumerFactory<Object, Object> consumerFactory(EmbeddedKafkaBroker broker) {
                Map<String, Object> props = new HashMap<>();
                props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
                props.put(ConsumerConfig.GROUP_ID_CONFIG, "sample-group");
                props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
                props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
                props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                props.put("spring.json.trusted.packages", "*");
                return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
                        ConsumerFactory<Object, Object> consumerFactory) {
                ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
                factory.setConsumerFactory(consumerFactory);
                return factory;
        }
}
