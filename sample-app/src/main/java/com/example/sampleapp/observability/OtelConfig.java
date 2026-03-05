package com.example.sampleapp.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Provides an {@link OpenTelemetry} bean backed by the OTel SDK with a logging metric exporter.
 *
 * <p>This configuration satisfies the {@code OpenTelemetry} dependency required by
 * {@link com.snekse.kafka.streams.resequence.config.ResequenceAutoConfiguration} when
 * OTel is on the classpath. Metrics are exported to the application log every 60 seconds
 * via {@link LoggingMetricExporter}.
 *
 * <p>In production, replace this with your preferred OTel SDK setup
 * (e.g., OTLP exporter, Prometheus exporter, or the OpenTelemetry Spring Boot Starter).
 */
@Configuration
public class OtelConfig {

    @Bean
    public OpenTelemetry openTelemetry() {
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(
                        PeriodicMetricReader.builder(LoggingMetricExporter.create())
                                .setInterval(Duration.ofSeconds(60))
                                .build())
                .build();

        return OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build();
    }
}
