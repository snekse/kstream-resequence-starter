package com.example.sampleapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SampleAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleAppApplication.class, args);
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.boot.CommandLineRunner runner(com.example.sampleapp.producer.SampleProducer producer) {
        return args -> {
            producer.produceSampleData();
        };
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.kafka.test.EmbeddedKafkaBroker embeddedKafkaBroker() {
        return new org.springframework.kafka.test.EmbeddedKafkaKraftBroker(1, 1, "sample-topic")
                .brokerListProperty("spring.kafka.bootstrap-servers");
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.kafka.core.KafkaAdmin kafkaAdmin(
            org.springframework.kafka.test.EmbeddedKafkaBroker broker) {
        java.util.Map<String, Object> configs = new java.util.HashMap<>();
        configs.put(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBrokersAsString());
        return new org.springframework.kafka.core.KafkaAdmin(configs);
    }

    @org.springframework.context.annotation.Bean
    public org.apache.kafka.clients.admin.NewTopic sampleTopic() {
        return org.springframework.kafka.config.TopicBuilder.name("sample-topic")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.kafka.core.ProducerFactory<Object, Object> producerFactory(
            org.springframework.kafka.test.EmbeddedKafkaBroker broker) {
        java.util.Map<String, Object> configProps = new java.util.HashMap<>();
        configProps.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBrokersAsString());
        configProps.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.LongSerializer.class);
        configProps.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.springframework.kafka.support.serializer.JsonSerializer.class);
        return new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(configProps);
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.kafka.core.KafkaTemplate<Object, Object> kafkaTemplate(
            org.springframework.kafka.core.ProducerFactory<Object, Object> producerFactory) {
        return new org.springframework.kafka.core.KafkaTemplate<>(producerFactory);
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.kafka.core.ConsumerFactory<Object, Object> consumerFactory(
            org.springframework.kafka.test.EmbeddedKafkaBroker broker) {
        java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBrokersAsString());
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "sample-group");
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.LongDeserializer.class);
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                org.springframework.kafka.support.serializer.JsonDeserializer.class);
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put("spring.json.trusted.packages", "*");
        return new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(props);
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            org.springframework.kafka.core.ConsumerFactory<Object, Object> consumerFactory) {
        org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

}
