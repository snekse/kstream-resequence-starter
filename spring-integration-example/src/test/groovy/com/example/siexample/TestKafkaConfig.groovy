package com.example.siexample

import com.example.sampledomain.SampleRecord
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer
import org.springframework.kafka.support.serializer.JacksonJsonSerializer
import org.springframework.kafka.test.EmbeddedKafkaBroker

@Configuration
@Profile('test')
class TestKafkaConfig {

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker

    @Autowired
    KafkaProperties kafkaProperties

    @Bean
    ProducerFactory<Object, Object> producerFactory() {
        Map<String, Object> props = kafkaProperties.buildProducerProperties()
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString())
        return new DefaultKafkaProducerFactory<>(props, null, new JacksonJsonSerializer<>())
    }

    @Bean
    ConsumerFactory<Object, Object> consumerFactory() {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties()
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString())
        def deserializer = new JacksonJsonDeserializer<>(SampleRecord)
        deserializer.addTrustedPackages('com.example.siexample.domain')
        return new DefaultKafkaConsumerFactory<>(props, null, deserializer)
    }

    @Bean
    KafkaTemplate<Object, Object> kafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory)
    }
}
