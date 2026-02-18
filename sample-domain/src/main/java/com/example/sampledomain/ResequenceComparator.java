package com.example.sampledomain;

import java.util.Comparator;
import java.util.Map;

public class ResequenceComparator implements Comparator<BufferedRecord<SampleRecord>> {

    private static final Map<String, Integer> OPERATION_ORDER = Map.of(
            "CREATE", 0,
            "UPDATE", 1,
            "DELETE", 2);

    private final TombstoneSortOrder tombstoneSortOrder;

    public ResequenceComparator(TombstoneSortOrder tombstoneSortOrder) {
        this.tombstoneSortOrder = tombstoneSortOrder;
    }

    @Override
    public int compare(BufferedRecord<SampleRecord> o1, BufferedRecord<SampleRecord> o2) {
        SampleRecord r1 = o1.getRecord();
        SampleRecord r2 = o2.getRecord();

        // Handle null records (tombstones) based on configuration
        if (r1 == null && r2 == null) {
            return 0;
        }
        if (r1 == null) {
            return tombstoneSortOrder.getSignum();
        }
        if (r2 == null) {
            return -tombstoneSortOrder.getSignum();
        }

        // 1. Operation Type
        int op1 = OPERATION_ORDER.getOrDefault(r1.getOperationType(), Integer.MAX_VALUE);
        int op2 = OPERATION_ORDER.getOrDefault(r2.getOperationType(), Integer.MAX_VALUE);
        if (op1 != op2) {
            return Integer.compare(op1, op2);
        }

        // 2. Payload timestamp
        if (r1.getTimestamp() != null && r2.getTimestamp() != null) {
            int timeCompare = r1.getTimestamp().compareTo(r2.getTimestamp());
            if (timeCompare != 0) {
                return timeCompare;
            }
        }

        // 3. Kafka metadata tiebreaker
        if (o1.getPartition() == o2.getPartition()) {
            return Long.compare(o1.getOffset(), o2.getOffset());
        }

        return Long.compare(o1.getTimestamp(), o2.getTimestamp());
    }
}
