package com.example.sampleapp.config;

import com.example.sampledomain.TombstoneSortOrder;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "resequence")
public class ResequenceProperties {

    private final String stateStoreName;
    private final Duration flushInterval;
    private final TombstoneSortOrder tombstoneSortOrder;

    public ResequenceProperties(String stateStoreName, Duration flushInterval, TombstoneSortOrder tombstoneSortOrder) {
        this.stateStoreName = stateStoreName != null ? stateStoreName : "resequence-buffer";
        this.flushInterval = flushInterval != null ? flushInterval : Duration.ofSeconds(2);
        this.tombstoneSortOrder = tombstoneSortOrder != null ? tombstoneSortOrder : TombstoneSortOrder.LAST;
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
}
