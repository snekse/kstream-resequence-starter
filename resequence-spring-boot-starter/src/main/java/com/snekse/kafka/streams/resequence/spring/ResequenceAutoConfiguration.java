package com.snekse.kafka.streams.resequence.spring;

import com.snekse.kafka.streams.resequence.Resequencer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ResequenceProperties.class)
public class ResequenceAutoConfiguration {

    /**
     * Pre-configured builder with properties-driven defaults. Consumers should add
     * domain-specific configuration (comparator, serdes) and call {@code build()}.
     */
    @Bean
    @ConditionalOnMissingBean
    public Resequencer.Builder<?, ?, ?, ?> resequencerBuilder(ResequenceProperties properties) {
        return Resequencer.builder()
            .stateStoreName(properties.getStateStoreName())
            .flushInterval(properties.getFlushInterval());
    }
}
