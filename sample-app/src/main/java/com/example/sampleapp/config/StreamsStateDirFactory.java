package com.example.sampleapp.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized helper for creating isolated Kafka Streams {@code state.dir} locations.
 *
 * <p>The sample app uses embedded Kafka brokers that are recreated between runs. If a previous
 * run's local Streams state is reused, its checkpointed changelog offsets can point to records that
 * no longer exist in the new broker, causing {@code OffsetOutOfRangeException} during state-store
 * restore. Creating a fresh state directory per run avoids stale checkpoints and makes local/test
 * runs deterministic.
 */
public final class StreamsStateDirFactory {

    private StreamsStateDirFactory() {
    }

    /**
     * Creates a unique temporary directory for Kafka Streams local state and marks it for deletion
     * on JVM exit.
     *
     * @param prefix prefix used for the temporary directory name
     * @return absolute path to an isolated state directory
     * @throws IllegalStateException if the directory cannot be created
     */
    public static String createIsolatedStateDir(String prefix) {
        try {
            Path stateDir = Files.createTempDirectory(prefix);
            stateDir.toFile().deleteOnExit();
            return stateDir.toAbsolutePath().toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create Kafka Streams state.dir", ex);
        }
    }
}
