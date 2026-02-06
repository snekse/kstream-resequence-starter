package com.example.sampleapp;

import com.example.sampleapp.producer.SampleProducer;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

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
}
