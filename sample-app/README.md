# Resequence Starter Sample App

This application demonstrates how to use the `resequence-starter` library.

## Running the App
Requires a running Kafka cluster.

```bash
./gradlew :sample-app:bootRun
```

## Configuration
Configure your Kafka brokers in `application.yml` (to be added).

## Ordering Challenges
Real-world systems often produce messages out of order. This sample demonstrates these challenges:

1. **Timestamp vs Offset**: A Child record might have the same timestamp as a Parent but be processed first due to partitions or network lag, violating the dependency constraint.
2. **Operation Order**: An UPDATE event might arrive before the CREATE event.
3. **Deletion**: A DELETE event must be processed last, but might arrive early.

The `OutOfOrderTest` generates these scenarios deterministically to verify that a naive consumer fails to process them correctly.
