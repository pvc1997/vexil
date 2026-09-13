package io.vexil.core.spi;

import io.vexil.core.model.Experiment;

import java.util.List;
import java.util.function.Consumer;

/**
 * Supplies experiment definitions to an engine.
 *
 * <p>Extension point: implementations may load from files, HTTP endpoints, databases, or a
 * streaming channel. Sources that can detect changes should also invoke the listener passed to
 * {@link #watch(Consumer)} with the full new experiment list whenever config changes.
 */
public interface ConfigSource extends AutoCloseable {

    /** Loads the current full set of experiments. Called once at engine startup. */
    List<Experiment> load();

    /**
     * Registers a listener for config changes. Static sources may keep the no-op default.
     * Implementations must deliver the complete experiment list on every change, not deltas.
     */
    default void watch(Consumer<List<Experiment>> listener) {
    }

    @Override
    default void close() {
    }
}
