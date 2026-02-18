package com.example.siexample.domain

import com.example.sampledomain.BufferedRecord
import com.example.sampledomain.EntityType
import com.example.sampledomain.ResequenceComparator
import com.example.sampledomain.SampleRecord
import com.example.sampledomain.TombstoneSortOrder
import spock.lang.Specification

class TombstoneSortOrderSpec extends Specification {

    def 'should sort tombstones to end with LAST configuration'() {
        given: 'comparator with LAST configuration'
        def comparator = new ResequenceComparator(TombstoneSortOrder.LAST)
        def baseTimestamp = System.currentTimeMillis()
        def normalRecord = bufferedRecord('UPDATE', baseTimestamp, 0, 0, baseTimestamp)
        def tombstone = BufferedRecord.builder()
                .record(null)
                .partition(0)
                .offset(1)
                .timestamp(baseTimestamp)
                .build()

        when: 'comparing normal record to tombstone'
        def result = comparator.compare(normalRecord, tombstone)

        then: 'normal record is less than tombstone (sorts before)'
        result < 0

        and: 'tombstone is greater than normal record (sorts after)'
        comparator.compare(tombstone, normalRecord) > 0
    }

    def 'should treat tombstones as equal with EQUAL configuration'() {
        given: 'comparator with EQUAL configuration'
        def comparator = new ResequenceComparator(TombstoneSortOrder.EQUAL)
        def baseTimestamp = System.currentTimeMillis()
        def normalRecord = bufferedRecord('UPDATE', baseTimestamp, 0, 0, baseTimestamp)
        def tombstone = BufferedRecord.builder()
                .record(null)
                .partition(0)
                .offset(1)
                .timestamp(baseTimestamp)
                .build()

        when: 'comparing normal record to tombstone'
        def result = comparator.compare(normalRecord, tombstone)

        then: 'they are considered equal'
        result == 0

        and: 'comparison in reverse order is also equal'
        comparator.compare(tombstone, normalRecord) == 0
    }

    def 'should sort tombstones to beginning with FIRST configuration'() {
        given: 'comparator with FIRST configuration'
        def comparator = new ResequenceComparator(TombstoneSortOrder.FIRST)
        def baseTimestamp = System.currentTimeMillis()
        def normalRecord = bufferedRecord('UPDATE', baseTimestamp, 0, 0, baseTimestamp)
        def tombstone = BufferedRecord.builder()
                .record(null)
                .partition(0)
                .offset(1)
                .timestamp(baseTimestamp)
                .build()

        when: 'comparing normal record to tombstone'
        def result = comparator.compare(normalRecord, tombstone)

        then: 'normal record is greater than tombstone (sorts after)'
        result > 0

        and: 'tombstone is less than normal record (sorts before)'
        comparator.compare(tombstone, normalRecord) < 0
    }

    def 'should handle sorting multiple tombstones with different configurations'() {
        given: 'records with tombstones'
        def baseTimestamp = System.currentTimeMillis()
        def create = bufferedRecord('CREATE', baseTimestamp, 0, 0, baseTimestamp)
        def update = bufferedRecord('UPDATE', baseTimestamp, 0, 1, baseTimestamp)
        def tombstone1 = BufferedRecord.builder()
                .record(null)
                .partition(0)
                .offset(2)
                .timestamp(baseTimestamp)
                .build()
        def tombstone2 = BufferedRecord.builder()
                .record(null)
                .partition(0)
                .offset(3)
                .timestamp(baseTimestamp)
                .build()

        when: 'sorting with LAST configuration'
        def comparatorLast = new ResequenceComparator(TombstoneSortOrder.LAST)
        def recordsLast = [tombstone1, update, tombstone2, create]
        recordsLast.sort(comparatorLast)

        then: 'non-null records come first'
        recordsLast[0].record.operationType == 'CREATE'
        recordsLast[1].record.operationType == 'UPDATE'
        recordsLast[2].record == null
        recordsLast[3].record == null

        when: 'sorting with FIRST configuration'
        def comparatorFirst = new ResequenceComparator(TombstoneSortOrder.FIRST)
        def recordsFirst = [update, create, tombstone1, tombstone2]
        recordsFirst.sort(comparatorFirst)

        then: 'tombstones come first'
        recordsFirst[0].record == null
        recordsFirst[1].record == null
        recordsFirst[2].record.operationType == 'CREATE'
        recordsFirst[3].record.operationType == 'UPDATE'
    }

    private BufferedRecord bufferedRecord(String operationType, long payloadTimestamp,
                                          int partition, long offset, long kafkaTimestamp) {
        def record = SampleRecord.builder()
                .clientId(1001L)
                .operationType(operationType)
                .timestamp(payloadTimestamp)
                .entityType(EntityType.Parent)
                .build()

        return BufferedRecord.builder()
                .record(record)
                .partition(partition)
                .offset(offset)
                .timestamp(kafkaTimestamp)
                .build()
    }
}
