package com.example.sampleapp.domain

import spock.lang.Specification

class ResequenceComparatorSpec extends Specification {

    def comparator = new ResequenceComparator()

    def 'should order by operation type: CREATE < UPDATE < DELETE'() {
        given: 'records with different operation types but same timestamps'
        def baseTimestamp = System.currentTimeMillis()
        def delete = bufferedRecord('DELETE', baseTimestamp, 0, 0, baseTimestamp)
        def update = bufferedRecord('UPDATE', baseTimestamp, 0, 1, baseTimestamp)
        def create = bufferedRecord('CREATE', baseTimestamp, 0, 2, baseTimestamp)

        when: 'sorted using the comparator'
        def records = [delete, update, create]
        records.sort(comparator)

        then: 'order is CREATE, UPDATE, DELETE'
        records[0].record.operationType == 'CREATE'
        records[1].record.operationType == 'UPDATE'
        records[2].record.operationType == 'DELETE'
    }

    def 'should order by payload timestamp when operation types are equal'() {
        given: 'UPDATE records with different payload timestamps'
        def baseTimestamp = System.currentTimeMillis()
        def update3 = bufferedRecord('UPDATE', baseTimestamp + 2000, 0, 0, baseTimestamp)
        def update1 = bufferedRecord('UPDATE', baseTimestamp, 0, 1, baseTimestamp)
        def update2 = bufferedRecord('UPDATE', baseTimestamp + 1000, 0, 2, baseTimestamp)

        when: 'sorted using the comparator'
        def records = [update3, update1, update2]
        records.sort(comparator)

        then: 'order is by ascending payload timestamp'
        records[0].record.timestamp == baseTimestamp
        records[1].record.timestamp == baseTimestamp + 1000
        records[2].record.timestamp == baseTimestamp + 2000
    }

    def 'should order by Kafka offset when same partition and same payload timestamp'() {
        given: 'UPDATE records with same payload timestamp on same partition'
        def payloadTimestamp = System.currentTimeMillis()
        def kafkaTimestamp = payloadTimestamp + 100
        def update3 = bufferedRecord('UPDATE', payloadTimestamp, 0, 30, kafkaTimestamp)
        def update1 = bufferedRecord('UPDATE', payloadTimestamp, 0, 10, kafkaTimestamp + 10)
        def update2 = bufferedRecord('UPDATE', payloadTimestamp, 0, 20, kafkaTimestamp + 5)

        when: 'sorted using the comparator'
        def records = [update3, update1, update2]
        records.sort(comparator)

        then: 'order is by ascending offset (lowest offset first)'
        records[0].offset == 10
        records[1].offset == 20
        records[2].offset == 30
    }

    def 'should order by Kafka timestamp when different partitions and same payload timestamp'() {
        given: 'UPDATE records with same payload timestamp on different partitions'
        def payloadTimestamp = System.currentTimeMillis()
        def update3 = bufferedRecord('UPDATE', payloadTimestamp, 2, 100, payloadTimestamp + 20)
        def update1 = bufferedRecord('UPDATE', payloadTimestamp, 0, 200, payloadTimestamp + 5)
        def update2 = bufferedRecord('UPDATE', payloadTimestamp, 1, 300, payloadTimestamp + 10)

        when: 'sorted using the comparator'
        def records = [update3, update1, update2]
        records.sort(comparator)

        then: 'order is by ascending Kafka timestamp (lowest timestamp first)'
        records[0].partition == 0
        records[1].partition == 1
        records[2].partition == 2
    }

    def 'should handle null records (tombstones) by sorting them to the end'() {
        given: 'records with some null values (tombstones)'
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

        when: 'sorted using the comparator'
        def records = [tombstone1, update, tombstone2, create]
        records.sort(comparator)

        then: 'non-null records come first, tombstones are at the end'
        records[0].record.operationType == 'CREATE'
        records[1].record.operationType == 'UPDATE'
        records[2].record == null
        records[3].record == null
    }

    def 'should compare two null records as equal'() {
        given: 'two tombstone records'
        def tombstone1 = BufferedRecord.builder()
                .record(null)
                .partition(0)
                .offset(0)
                .timestamp(1000L)
                .build()
        def tombstone2 = BufferedRecord.builder()
                .record(null)
                .partition(1)
                .offset(1)
                .timestamp(2000L)
                .build()

        when: 'comparing them'
        def result = comparator.compare(tombstone1, tombstone2)

        then: 'they are considered equal'
        result == 0
    }

    def 'should sort null record after non-null record'() {
        given: 'one tombstone and one normal record'
        def normalRecord = bufferedRecord('UPDATE', 1000L, 0, 0, 1000L)
        def tombstone = BufferedRecord.builder()
                .record(null)
                .partition(0)
                .offset(1)
                .timestamp(500L)
                .build()

        expect: 'tombstone is greater than normal record'
        comparator.compare(tombstone, normalRecord) > 0

        and: 'normal record is less than tombstone'
        comparator.compare(normalRecord, tombstone) < 0
    }

    def 'should handle complex scenario with multiple ordering criteria'() {
        given: 'a mix of records exercising all ordering rules'
        def baseTime = 1000000L

        // CREATE should come first regardless of timestamps
        def create = bufferedRecord('CREATE', baseTime + 5000, 0, 100, baseTime + 5000)

        // Two UPDATEs with different payload timestamps
        def updateEarly = bufferedRecord('UPDATE', baseTime + 1000, 0, 10, baseTime + 1000)
        def updateLate = bufferedRecord('UPDATE', baseTime + 2000, 0, 20, baseTime + 2000)

        // Two UPDATEs with same payload timestamp, same partition - use offset
        def updateSameTime1 = bufferedRecord('UPDATE', baseTime + 3000, 1, 5, baseTime + 3010)
        def updateSameTime2 = bufferedRecord('UPDATE', baseTime + 3000, 1, 15, baseTime + 3005)

        // Two UPDATEs with same payload timestamp, different partitions - use Kafka timestamp
        def updateDiffPartition1 = bufferedRecord('UPDATE', baseTime + 4000, 2, 50, baseTime + 4020)
        def updateDiffPartition2 = bufferedRecord('UPDATE', baseTime + 4000, 3, 60, baseTime + 4010)

        // DELETE should come last
        def delete = bufferedRecord('DELETE', baseTime, 0, 1, baseTime)

        when: 'all records are sorted'
        def records = [delete, updateDiffPartition1, updateSameTime2, create, updateLate,
                       updateSameTime1, updateEarly, updateDiffPartition2]
        records.sort(comparator)

        then: 'CREATE comes first'
        records[0].record.operationType == 'CREATE'

        and: 'UPDATEs are in the middle, ordered by their respective criteria'
        records[1].record.operationType == 'UPDATE'
        records[2].record.operationType == 'UPDATE'
        records[3].record.operationType == 'UPDATE'
        records[4].record.operationType == 'UPDATE'
        records[5].record.operationType == 'UPDATE'
        records[6].record.operationType == 'UPDATE'

        and: 'DELETE comes last'
        records[7].record.operationType == 'DELETE'

        and: 'UPDATEs with different payload timestamps are ordered by payload timestamp'
        records[1].record.timestamp == baseTime + 1000
        records[2].record.timestamp == baseTime + 2000

        and: 'UPDATEs with same payload timestamp, same partition are ordered by offset'
        records[3].record.timestamp == baseTime + 3000
        records[4].record.timestamp == baseTime + 3000
        records[3].offset < records[4].offset

        and: 'UPDATEs with same payload timestamp, different partitions are ordered by Kafka timestamp'
        records[5].record.timestamp == baseTime + 4000
        records[6].record.timestamp == baseTime + 4000
        records[5].timestamp < records[6].timestamp
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
