package com.example.siexample;

import com.example.siexample.producer.SampleProducer;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@SpringBootApplication
public class SiExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SiExampleApplication.class, args);
    }

    @Bean
    @Profile("!test")
    public CommandLineRunner runner(SampleProducer producer) {
        return args -> producer.produceSampleData();
    }
}
