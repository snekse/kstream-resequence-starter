package com.example.sampleapp

import org.apache.kafka.streams.StreamsConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration
import org.springframework.kafka.config.KafkaStreamsConfiguration
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
}
