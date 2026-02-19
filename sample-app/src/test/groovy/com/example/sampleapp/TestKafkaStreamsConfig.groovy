package com.example.sampleapp

import com.example.sampleapp.domain.SampleRecord
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.StreamsConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration
import org.springframework.kafka.config.KafkaStreamsConfiguration
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker

@Configuration
@Profile('test')
class TestKafkaStreamsConfig {

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker

    @Autowired
    KafkaProperties kafkaProperties

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    KafkaStreamsConfiguration kStreamsConfigs() {
        Map<String, Object> props = new HashMap<>()
        props.putAll(kafkaProperties.buildStreamsProperties())
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString())
        return new KafkaStreamsConfiguration(props)
    }

    @Bean
    ProducerFactory<Object, Object> producerFactory(Serde<SampleRecord> sampleRecordSerde) {
        Map<String, Object> props = kafkaProperties.buildProducerProperties()
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString())
        return new DefaultKafkaProducerFactory<>(props, null, sampleRecordSerde.serializer())
    }

    @Bean
    ConsumerFactory<Object, Object> consumerFactory(Serde<SampleRecord> sampleRecordSerde) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties()
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString())
        return new DefaultKafkaConsumerFactory<>(props, null, sampleRecordSerde.deserializer())
    }
}
