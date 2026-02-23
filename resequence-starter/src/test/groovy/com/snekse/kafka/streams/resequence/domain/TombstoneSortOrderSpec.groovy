package com.snekse.kafka.streams.resequence.domain

import spock.lang.Specification

class TombstoneSortOrderSpec extends Specification {

    def 'should have correct enum values'() {
        expect:
        TombstoneSortOrder.values().length == 3
        TombstoneSortOrder.valueOf('FIRST') == TombstoneSortOrder.FIRST
        TombstoneSortOrder.valueOf('EQUAL') == TombstoneSortOrder.EQUAL
        TombstoneSortOrder.valueOf('LAST') == TombstoneSortOrder.LAST
    }

    def 'should have correct signum for #order'() {
        expect:
        order.getSignum() == expectedSignum

        where:
        order                     | expectedSignum
        TombstoneSortOrder.FIRST  | -1
        TombstoneSortOrder.EQUAL  | 0
        TombstoneSortOrder.LAST   | 1
    }
}
