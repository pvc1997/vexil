package io.vexil.server;

import io.vexil.core.model.Experiment;
import io.vexil.core.spi.ConfigSource;

import java.io.IOException;

/**
 * A {@link ConfigSource} that also accepts mutations — the contract the server's admin API
 * writes through. Implementations must notify watchers after every successful mutation.
 */
public interface ConfigStore extends ConfigSource {

    /** Creates or replaces the experiment with the definition's key. */
    void putExperiment(Experiment experiment) throws IOException;

    /** Deletes the experiment with the given key; returns false if it did not exist. */
    boolean deleteExperiment(String key) throws IOException;
}
