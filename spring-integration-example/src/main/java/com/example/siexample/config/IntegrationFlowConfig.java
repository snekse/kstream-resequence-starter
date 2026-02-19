package com.example.siexample.config;

import com.example.sampledomain.BufferedRecord;
import com.example.sampledomain.ResequenceComparator;
import com.example.sampledomain.SampleRecord;
import com.example.siexample.processor.KeyMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.aggregator.MessageGroupProcessor;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.kafka.dsl.Kafka;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

@Configuration
@EnableConfigurationProperties(ResequenceProperties.class)
public class IntegrationFlowConfig {

    @Bean
    public Comparator<BufferedRecord<SampleRecord>> resequenceComparator(ResequenceProperties properties) {
        return new ResequenceComparator(properties.getTombstoneSortOrder());
    }

    @Bean
    public KeyMapper<String, String> keyMapper() {
        return key -> key + "-sorted";
    }

    @Bean
    public BiConsumer<String, SampleRecord> valueEnricher() {
        return (newKey, record) -> record.setNewKey(newKey);
    }

    @Bean
    @SuppressWarnings("unchecked")
    public MessageGroupProcessor resequenceOutputProcessor(
            Comparator<BufferedRecord<SampleRecord>> resequenceComparator,
            KeyMapper<String, String> keyMapper,
            BiConsumer<String, SampleRecord> valueEnricher) {
        return group -> {
            List<Message<?>> messages = group.streamMessages().toList();

            // Sort buffered records using comparator
            List<Message<?>> sorted = messages.stream()
                    .sorted(Comparator.comparing(m -> (BufferedRecord<SampleRecord>) m.getPayload(), resequenceComparator))
                    .toList();

            // Unwrap BufferedRecords, apply key mapping and value enrichment
            return sorted.stream()
                    .map(msg -> {
                        @SuppressWarnings("unchecked")
                        BufferedRecord<SampleRecord> buffered = (BufferedRecord<SampleRecord>) msg.getPayload();
                        String originalKey = msg.getHeaders().get(KafkaHeaders.RECEIVED_KEY, String.class);
                        String mappedKey = originalKey != null ? keyMapper.map(originalKey) : null;

                        SampleRecord record = buffered.getRecord();
                        if (record != null && mappedKey != null) {
                            valueEnricher.accept(mappedKey, record);
                        }

                        return MessageBuilder.withPayload(record != null ? (Object) record : "TOMBSTONE")
                                .setHeader("mappedKey", mappedKey)
                                .setHeader("isTombstone", record == null)
                                .build();
                    })
                    .toList();
        };
    }

    @Bean
    public IntegrationFlow resequenceFlow(
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            ResequenceProperties resequenceProperties,
            MessageGroupProcessor resequenceOutputProcessor,
            @Value("${app.pipeline.source.topic}") String sourceTopic,
            @Value("${app.pipeline.sink.topic}") String sinkTopic) {

        return IntegrationFlow
                .from(Kafka.messageDrivenChannelAdapter(consumerFactory, sourceTopic))
                // Filter null keys + wrap payload in BufferedRecord with Kafka metadata
                .handle((payload, headers) -> {
                    Object key = headers.get(KafkaHeaders.RECEIVED_KEY);
                    if (key == null) {
                        return null; // drop null-keyed messages
                    }

                    SampleRecord record = payload instanceof SampleRecord sr ? sr : null;

                    Integer partition = (Integer) headers.get(KafkaHeaders.RECEIVED_PARTITION);
                    Long offset = (Long) headers.get(KafkaHeaders.OFFSET);
                    Long kafkaTimestamp = (Long) headers.get(KafkaHeaders.RECEIVED_TIMESTAMP);

                    return BufferedRecord.<SampleRecord>builder()
                            .record(record)
                            .partition(partition != null ? partition : 0)
                            .offset(offset != null ? offset : 0L)
                            .timestamp(kafkaTimestamp != null ? kafkaTimestamp : 0L)
                            .build();
                })
                // Aggregate: group by original Kafka key, release on timeout
                .aggregate(a -> a
                        .correlationStrategy(m -> m.getHeaders().get(KafkaHeaders.RECEIVED_KEY))
                        .releaseStrategy(g -> false) // Never release early, only on timeout
                        .groupTimeout(resequenceProperties.getGroupTimeout().toMillis())
                        .sendPartialResultOnExpiry(true)
                        .expireGroupsUponCompletion(true)
                        .outputProcessor(resequenceOutputProcessor))
                // Split: break sorted list back into individual messages
                .split()
                // Handle: send each message to sink topic
                .handle(m -> {
                    Boolean isTombstone = m.getHeaders().get("isTombstone", Boolean.class);
                    String mappedKey = m.getHeaders().get("mappedKey", String.class);

                    if (Boolean.TRUE.equals(isTombstone)) {
                        kafkaTemplate.send(sinkTopic, mappedKey, null);
                    } else {
                        kafkaTemplate.send(sinkTopic, mappedKey, m.getPayload());
                    }
                })
                .get();
    }
}
