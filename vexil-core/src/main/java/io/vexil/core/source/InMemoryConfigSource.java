package io.vexil.core.source;

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

    private volatile List<Experiment> experiments;
    private final List<Consumer<List<Experiment>>> listeners = new CopyOnWriteArrayList<>();

    public InMemoryConfigSource(List<Experiment> experiments) {
        this.experiments = List.copyOf(experiments);
    }

    @Override
    public List<Experiment> load() {
        return experiments;
    }

    @Override
    public void watch(Consumer<List<Experiment>> listener) {
        listeners.add(listener);
    }

    /** Replaces the experiment set and notifies all watchers. */
    public void update(List<Experiment> updated) {
        this.experiments = List.copyOf(updated);
        for (Consumer<List<Experiment>> listener : listeners) {
            listener.accept(this.experiments);
        }
    }
}
