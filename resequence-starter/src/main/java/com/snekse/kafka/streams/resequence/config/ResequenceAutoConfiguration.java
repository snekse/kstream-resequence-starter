package com.snekse.kafka.streams.resequence.config;

import com.snekse.kafka.streams.resequence.listener.OtelResequenceEventListener;
import com.snekse.kafka.streams.resequence.listener.ResequenceEventListener;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ResequenceProperties.class)
public class ResequenceAutoConfiguration {

    /**
     * Registers an OTel-backed listener when:
     * <ol>
     *   <li>{@code opentelemetry-api} is on the runtime classpath</li>
     *   <li>No user-provided {@link ResequenceEventListener} bean exists</li>
     *   <li>{@code resequence.otel.enabled} is not {@code false}</li>
     * </ol>
     * Uses the string form of {@code @ConditionalOnClass} because {@code opentelemetry-api}
     * is {@code compileOnly} in this module — referencing the class literal directly would
     * cause a {@code NoClassDefFoundError} when OTel is absent at runtime.
     */
    @Bean
    @ConditionalOnClass(name = "io.opentelemetry.api.OpenTelemetry")
    @ConditionalOnMissingBean(ResequenceEventListener.class)
    @ConditionalOnProperty(prefix = "resequence.otel", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ResequenceEventListener otelResequenceEventListener(OpenTelemetry openTelemetry) {
        return new OtelResequenceEventListener(openTelemetry);
    }

    /**
     * Fallback no-op listener registered when OTel is absent, disabled, or a user-provided
     * {@link ResequenceEventListener} bean already exists.
     */
    @Bean
    @ConditionalOnMissingBean(ResequenceEventListener.class)
    public ResequenceEventListener noOpResequenceEventListener() {
        return ResequenceEventListener.noOp();
    }
}
