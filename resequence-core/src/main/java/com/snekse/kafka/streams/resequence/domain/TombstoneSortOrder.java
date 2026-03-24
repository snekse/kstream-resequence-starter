package com.snekse.kafka.streams.resequence.domain;

public enum TombstoneSortOrder {
    FIRST(-1),
    EQUAL(0),
    LAST(1);

    private final int signum;

    TombstoneSortOrder(int signum) {
        this.signum = signum;
    }

    public int getSignum() {
        return signum;
    }
}
