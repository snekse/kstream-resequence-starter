# Resequence Starter Sample App

This application demonstrates how to use the `resequence-starter` library.

## Running the App

The sample app uses embedded Kafka, so no external cluster is required.

```bash
# Run with default behavior (auto-shuts down after 30 seconds)
./gradlew :sample-app:bootRun

# Run and auto-shutdown after a custom delay
./gradlew :sample-app:bootRun --args='--app.auto-shutdown-delay=10'

# Run and stay alive indefinitely (Ctrl+C to stop)
./gradlew :sample-app:bootRun --args='--app.auto-shutdown=false'
```

### CLI Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `app.auto-shutdown` | `true` | When `true`, the app exits automatically after the delay. Set to `false` to keep the app running indefinitely. |
| `app.auto-shutdown-delay` | `30` | Seconds to wait after producing before shutting down. Ignored when `app.auto-shutdown=false`. |

## Configuration
Configure your Kafka brokers in `application.yml` (to be added).

## Ordering Challenges
Real-world systems often produce messages out of order. This sample demonstrates these challenges:

1. **Timestamp vs Offset**: A Child record might have the same timestamp as a Parent but be processed first due to partitions or network lag, violating the dependency constraint.
2. **Operation Order**: An UPDATE event might arrive before the CREATE event.
3. **Deletion**: A DELETE event must be processed last, but might arrive early.

The `OutOfOrderTest` generates these scenarios deterministically to verify that a naive consumer fails to process them correctly.
