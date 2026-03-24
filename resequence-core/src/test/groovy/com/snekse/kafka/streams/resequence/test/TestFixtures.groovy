package com.snekse.kafka.streams.resequence.test

import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig

class TestFixtures {

    static class TestRecord {
        String type
        long ts

        TestRecord() {}
        TestRecord(String type, long ts) {
            this.type = type
            this.ts = ts
        }
    }

    static Properties driverConfig() {
        def props = new Properties()
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, 'test-app')
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.name)
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.name)
        props
    }
}
