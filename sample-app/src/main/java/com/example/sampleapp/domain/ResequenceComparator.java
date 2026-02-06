package com.example.sampleapp.domain;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;

@Component
public class ResequenceComparator implements Comparator<BufferedRecord> {

    // Define order: CREATE (0) < UPDATE (1) < DELETE (2)
    // Note: User prompt said "CREATE > UPDATE > DELETE", but context implies sort
    // order.
    // Typically we process Create first. I will assume standard logical order.
    private static final Map<String, Integer> OPERATION_ORDER = Map.of(
            "CREATE", 0,
            "UPDATE", 1,
            "DELETE", 2);

    @Override
    public int compare(BufferedRecord o1, BufferedRecord o2) {
        SampleRecord r1 = o1.getRecord();
        SampleRecord r2 = o2.getRecord();

        // 1. Operation Type
        int op1 = OPERATION_ORDER.getOrDefault(r1.getOperationType(), Integer.MAX_VALUE);
        int op2 = OPERATION_ORDER.getOrDefault(r2.getOperationType(), Integer.MAX_VALUE);
        if (op1 != op2) {
            return Integer.compare(op1, op2);
        }

        // 2. Timestamp (Payload timestamp prefers, usage depends on exact requirement,
        // prompt says "use the `timestamp` as the fallback comparison" - usually
        // referring to payload timestamp if available, or kafka timestamp)
        // Let's use payload timestamp if available.
        // The generator creates payload timestamp.
        if (r1.getTimestamp() != null && r2.getTimestamp() != null) {
            int timeCompare = r1.getTimestamp().compareTo(r2.getTimestamp());
            if (timeCompare != 0) {
                return timeCompare;
            }
        }

        // 3. Fallback: Kafka Header Details
        // "if they were on the same partition, then lowest offset is first"
        if (o1.getPartition() == o2.getPartition()) {
            return Long.compare(o1.getOffset(), o2.getOffset());
        }

        // "if they were on different partitions, lowest kafka timestamp wins"
        // Note: Kafka timestamp is captured in BufferedRecord.timestamp
        return Long.compare(o1.getTimestamp(), o2.getTimestamp());
    }
}
