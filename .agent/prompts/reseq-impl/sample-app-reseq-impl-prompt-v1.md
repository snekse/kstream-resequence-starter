As a developer, I want to see `sample-app` module implement a KStream application that will be a simple rekey processor.  It will just append "-sorted" to the existing key.

For this, we will now move from 1 topic to 2 topics.  The producer will produce to the existing topic, and the consumer will consume from a new topic.  The new topic will have the name of the existing topic with the suffix "-resequenced".  This new topic will use a String key instead of a Long key.

When done, we should still have "out of order" events, but now the keys will be Strings.  In our payload, add a field named `newKey`.  In the KStream impl, in addition to rekeying the topic, we will enrich the payload to add this new key to the payload itself (the producer will leave the field null, but the consumer should be able to assert this field is never null - assert this in our tests)

Spring Kafka 4 has support for Kafka Streams. While planning, read the docs to make sure your knowledge is up to date

https://raw.githubusercontent.com/spring-projects/spring-kafka/refs/tags/v4.0.1/spring-kafka-docs/src/main/antora/modules/ROOT/pages/streams.adoc

If you have questions before we begin, be sure to add them in your Planning artifact
