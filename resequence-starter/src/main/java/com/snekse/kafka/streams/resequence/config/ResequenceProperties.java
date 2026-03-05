package com.snekse.kafka.streams.resequence.config;

import com.snekse.kafka.streams.resequence.domain.TombstoneSortOrder;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "resequence")
public class ResequenceProperties {

    private final String stateStoreName;
    private final Duration flushInterval;
    private final TombstoneSortOrder tombstoneSortOrder;
    private final OtelProperties otel;

    public ResequenceProperties(String stateStoreName, Duration flushInterval,
                                TombstoneSortOrder tombstoneSortOrder, OtelProperties otel) {
        this.stateStoreName = stateStoreName != null ? stateStoreName : "resequence-buffer";
        this.flushInterval = flushInterval != null ? flushInterval : Duration.ofSeconds(2);
        this.tombstoneSortOrder = tombstoneSortOrder != null ? tombstoneSortOrder : TombstoneSortOrder.LAST;
        this.otel = otel != null ? otel : new OtelProperties(null);
    }

    public String getStateStoreName() {
        return stateStoreName;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public TombstoneSortOrder getTombstoneSortOrder() {
        return tombstoneSortOrder;
    }

    public OtelProperties getOtel() {
        return otel;
    }

    public static class OtelProperties {

        private final boolean enabled;

        public OtelProperties(Boolean enabled) {
            this.enabled = enabled != null ? enabled : true;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }
}
