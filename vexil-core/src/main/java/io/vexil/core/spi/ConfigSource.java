package io.vexil.core.spi;

import io.vexil.core.model.ConfigSnapshot;

import java.util.function.Consumer;

/**
 * Supplies experimentation configuration to an engine.
 *
 * <p>Extension point: implementations may load from files, HTTP endpoints, databases, or a
 * streaming channel. Sources that can detect changes should also invoke the listener passed to
 * {@link #watch(Consumer)} with the full new snapshot whenever config changes.
 */
public interface ConfigSource extends AutoCloseable {

    /** Loads the current full configuration snapshot. Called once at engine startup. */
    ConfigSnapshot load();

    /**
     * Registers a listener for config changes. Static sources may keep the no-op default.
     * Implementations must deliver complete snapshots on every change, not deltas.
     */
    default void watch(Consumer<ConfigSnapshot> listener) {
    }

    @Override
    default void close() {
    }
}
