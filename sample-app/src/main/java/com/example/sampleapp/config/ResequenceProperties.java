package com.example.sampleapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "resequence")
public class ResequenceProperties {

    private String stateStoreName = "resequence-buffer";
    private Duration flushInterval = Duration.ofSeconds(2);
    private TombstoneSortOrder tombstoneSortOrder = TombstoneSortOrder.LAST;

    public String getStateStoreName() {
        return stateStoreName;
    }

    public void setStateStoreName(String stateStoreName) {
        this.stateStoreName = stateStoreName;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }

    public TombstoneSortOrder getTombstoneSortOrder() {
        return tombstoneSortOrder;
    }

    public void setTombstoneSortOrder(TombstoneSortOrder tombstoneSortOrder) {
        this.tombstoneSortOrder = tombstoneSortOrder;
    }
}
