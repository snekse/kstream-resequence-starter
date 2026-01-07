package com.example.sampleapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import com.example.sampleapp.producer.SampleProducer;

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
                return new EmbeddedKafkaKraftBroker(1, 3, topic)
                                .brokerListProperty("spring.kafka.bootstrap-servers");
        }
}
