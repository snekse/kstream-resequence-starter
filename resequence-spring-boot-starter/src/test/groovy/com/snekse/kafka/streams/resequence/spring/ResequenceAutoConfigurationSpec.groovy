package com.snekse.kafka.streams.resequence.spring

import com.snekse.kafka.streams.resequence.Resequencer
import com.snekse.kafka.streams.resequence.domain.TombstoneSortOrder
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.Specification

import java.time.Duration

class ResequenceAutoConfigurationSpec extends Specification {

    ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ResequenceAutoConfiguration))

    def 'should create builder and properties beans with defaults'() {
        expect:
        contextRunner.run { context ->
            assert context.containsBean('resequencerBuilder')
            assert context.getBean(Resequencer.Builder) != null

            def props = context.getBean(ResequenceProperties)
            assert props.stateStoreName == 'resequence-buffer'
            assert props.flushInterval == Duration.ofSeconds(2)
            assert props.tombstoneSortOrder == TombstoneSortOrder.LAST
        }
    }

    def 'should apply custom properties'() {
        given:
        def runner = contextRunner.withPropertyValues(
            'resequence.state-store-name=custom-store',
            'resequence.flush-interval=5s',
            'resequence.tombstone-sort-order=FIRST'
        )

        expect:
        runner.run { context ->
            def props = context.getBean(ResequenceProperties)
            assert props.stateStoreName == 'custom-store'
            assert props.flushInterval == Duration.ofSeconds(5)
            assert props.tombstoneSortOrder == TombstoneSortOrder.FIRST
        }
    }

    def 'should allow overriding builder bean'() {
        given:
        def runner = contextRunner.withBean(Resequencer.Builder, { Resequencer.builder().stateStoreName('overridden') })

        expect:
        runner.run { context ->
            assert context.getBean(Resequencer.Builder) != null
            // The overridden bean should be used instead of the auto-configured one
            assert !context.containsBean('resequencerBuilder')
        }
    }
}
