package com.example.sampleapp.observability;

import com.snekse.kafka.streams.resequence.listener.OtelResequenceEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Logs a one-line resequencer metrics summary to the console when the application shuts down.
 *
 * <p>Only active when an {@link OtelResequenceEventListener} bean is present (i.e., OTel
 * is on the classpath and {@code resequence.otel.enabled} is not {@code false}).
 */
@Component
@ConditionalOnBean(OtelResequenceEventListener.class)
public class MetricsShutdownLogger implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(MetricsShutdownLogger.class);

    private final OtelResequenceEventListener listener;

    public MetricsShutdownLogger(OtelResequenceEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Resequencer metrics summary — buffered={} tombstones={} ignored={} forwarded={} flushes={}",
                listener.getTotalRecordsBuffered(),
                listener.getTotalTombstonesReceived(),
                listener.getTotalRecordsIgnored(),
                listener.getTotalRecordsForwarded(),
                listener.getTotalFlushes());
    }
}
