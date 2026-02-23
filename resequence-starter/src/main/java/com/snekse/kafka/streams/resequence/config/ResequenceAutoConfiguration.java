package com.snekse.kafka.streams.resequence.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@EnableConfigurationProperties(ResequenceProperties.class)
public class ResequenceAutoConfiguration {
}
