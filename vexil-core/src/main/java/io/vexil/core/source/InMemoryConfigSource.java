package io.vexil.core.source;

import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.model.Experiment;
import io.vexil.core.spi.ConfigSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A mutable in-process config source — useful for tests, demos, and embedding Vexil in an
 * application that manages experiment definitions itself.
 */
public final class InMemoryConfigSource implements ConfigSource {

    private volatile ConfigSnapshot snapshot;
    private final List<Consumer<ConfigSnapshot>> listeners = new CopyOnWriteArrayList<>();

    public InMemoryConfigSource(ConfigSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public InMemoryConfigSource(List<Experiment> experiments) {
        this(ConfigSnapshot.of(experiments));
    }

    @Override
    public ConfigSnapshot load() {
        return snapshot;
    }

    @Override
    public void watch(Consumer<ConfigSnapshot> listener) {
        listeners.add(listener);
    }

    /** Replaces the configuration and notifies all watchers. */
    public void update(ConfigSnapshot updated) {
        this.snapshot = updated;
        for (Consumer<ConfigSnapshot> listener : listeners) {
            listener.accept(updated);
        }
    }

    /** Replaces the experiment set (keeping no holdouts) and notifies all watchers. */
    public void update(List<Experiment> experiments) {
        update(ConfigSnapshot.of(experiments));
    }
}
