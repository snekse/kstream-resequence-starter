package com.example.sampleapp;

import com.example.sampleapp.producer.SampleProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }

    @Bean
    @Profile("!test")
    public CommandLineRunner runner(
            SampleProducer producer,
            ApplicationContext ctx,
            @Value("${app.auto-shutdown:true}") boolean autoShutdown,
            @Value("${app.auto-shutdown-delay:30}") int autoShutdownDelaySecs) {
        return args -> {
            producer.produceSampleData();
            if (autoShutdown) {
                log.info("Auto-shutdown in {} seconds...", autoShutdownDelaySecs);
                Executors.newSingleThreadScheduledExecutor()
                        .schedule(() -> {
                            log.info("Shutting down.");
                            System.exit(SpringApplication.exit(ctx));
                        }, autoShutdownDelaySecs, TimeUnit.SECONDS);
            }
        };
    }
}
