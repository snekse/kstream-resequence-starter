package com.example.siexample.config;

import com.example.sampledomain.TombstoneSortOrder;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "resequence")
public class ResequenceProperties {

    private final Duration groupTimeout;
    private final TombstoneSortOrder tombstoneSortOrder;

    public ResequenceProperties(Duration groupTimeout, TombstoneSortOrder tombstoneSortOrder) {
        this.groupTimeout = groupTimeout != null ? groupTimeout : Duration.ofSeconds(2);
        this.tombstoneSortOrder = tombstoneSortOrder != null ? tombstoneSortOrder : TombstoneSortOrder.LAST;
    }

    public Duration getGroupTimeout() {
        return groupTimeout;
    }

    public TombstoneSortOrder getTombstoneSortOrder() {
        return tombstoneSortOrder;
    }
}
