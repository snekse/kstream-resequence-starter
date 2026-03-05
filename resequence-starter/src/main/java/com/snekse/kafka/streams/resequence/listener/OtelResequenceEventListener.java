package com.snekse.kafka.streams.resequence.listener;

import com.snekse.kafka.streams.resequence.domain.BufferedRecord;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ResequenceEventListener} implementation that emits metrics via the OpenTelemetry API.
 *
 * <p>All OTel instruments are created once in the constructor from the provided
 * {@link OpenTelemetry} instance. In addition to OTel instruments, simple {@link AtomicLong}
 * accumulators are maintained so that callers can read lifetime totals without needing access
 * to an OTel {@code MetricReader}.
 *
 * <p>This class is safe to use with KStreams processors because Kafka Streams processor tasks
 * are single-threaded. The plain {@code long} timing fields ({@code flushStartNanos},
 * {@code lastFlushWasFullScan}) are not synchronized for this reason.
 */
public class OtelResequenceEventListener implements ResequenceEventListener {

    private static final String INSTRUMENTATION_NAME = "com.snekse.kafka.streams.resequence";
    private static final AttributeKey<Boolean> FULL_SCAN = AttributeKey.booleanKey("full_scan");

    // OTel instruments
    private final LongCounter recordsBuffered;
    private final LongCounter tombstonesReceived;
    private final LongCounter recordsIgnored;
    private final LongCounter recordsForwarded;
    private final LongCounter flushCount;
    private final LongHistogram flushDurationMs;
    private final LongHistogram bufferSize;

    // Lifetime accumulators for shutdown summary logging (no MetricReader needed)
    private final AtomicLong totalRecordsBuffered = new AtomicLong();
    private final AtomicLong totalTombstonesReceived = new AtomicLong();
    private final AtomicLong totalRecordsIgnored = new AtomicLong();
    private final AtomicLong totalRecordsForwarded = new AtomicLong();
    private final AtomicLong totalFlushes = new AtomicLong();

    // Flush timing state — safe as plain fields because KStreams is single-threaded per task
    private long flushStartNanos;
    private boolean lastFlushWasFullScan;

    public OtelResequenceEventListener(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_NAME);

        recordsBuffered = meter.counterBuilder("resequence.records.buffered")
                .setDescription("Number of records appended to the resequence buffer (includes tombstones)")
                .setUnit("{record}")
                .build();

        tombstonesReceived = meter.counterBuilder("resequence.tombstones.received")
                .setDescription("Number of tombstone (null-value) records buffered")
                .setUnit("{record}")
                .build();

        recordsIgnored = meter.counterBuilder("resequence.records.ignored")
                .setDescription("Number of records silently ignored due to null key")
                .setUnit("{record}")
                .build();

        recordsForwarded = meter.counterBuilder("resequence.records.forwarded")
                .setDescription("Number of records sorted and forwarded downstream")
                .setUnit("{record}")
                .build();

        flushCount = meter.counterBuilder("resequence.flushes")
                .setDescription("Number of flush cycles completed")
                .setUnit("{flush}")
                .build();

        flushDurationMs = meter.histogramBuilder("resequence.flush.duration")
                .ofLongs()
                .setDescription("Duration of each flush cycle in milliseconds")
                .setUnit("ms")
                .build();

        bufferSize = meter.histogramBuilder("resequence.buffer.size")
                .ofLongs()
                .setDescription("Number of records per key at flush time")
                .setUnit("{record}")
                .build();
    }

    @Override
    public void onRecordBuffered(Object key, BufferedRecord<?> record) {
        recordsBuffered.add(1);
        totalRecordsBuffered.incrementAndGet();
    }

    @Override
    public void onTombstoneReceived(Object key, BufferedRecord<?> record) {
        tombstonesReceived.add(1);
        totalTombstonesReceived.incrementAndGet();
    }

    @Override
    public void onRecordIgnored() {
        recordsIgnored.add(1);
        totalRecordsIgnored.incrementAndGet();
    }

    @Override
    public void onFlushStarted(boolean isFullScan) {
        this.lastFlushWasFullScan = isFullScan;
        this.flushStartNanos = System.nanoTime();
    }

    @Override
    public void onFlushCompleted(int keysCount, int recordsCount) {
        long durationMs = (System.nanoTime() - flushStartNanos) / 1_000_000;
        flushDurationMs.record(durationMs);
        flushCount.add(1, Attributes.of(FULL_SCAN, lastFlushWasFullScan));
        totalRecordsForwarded.addAndGet(recordsCount);
        totalFlushes.incrementAndGet();
    }

    @Override
    public void onKeyFlushed(Object key, int recordCount) {
        recordsForwarded.add(recordCount);
        bufferSize.record(recordCount);
    }

    /** Returns the total number of records buffered since this listener was created. */
    public long getTotalRecordsBuffered() { return totalRecordsBuffered.get(); }

    /** Returns the total number of tombstone records buffered since this listener was created. */
    public long getTotalTombstonesReceived() { return totalTombstonesReceived.get(); }

    /** Returns the total number of records ignored (null key) since this listener was created. */
    public long getTotalRecordsIgnored() { return totalRecordsIgnored.get(); }

    /** Returns the total number of records forwarded downstream since this listener was created. */
    public long getTotalRecordsForwarded() { return totalRecordsForwarded.get(); }

    /** Returns the total number of flush cycles completed since this listener was created. */
    public long getTotalFlushes() { return totalFlushes.get(); }
}
