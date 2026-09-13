package io.vexil.server;

import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.model.Experiment;
import io.vexil.core.spi.ConfigSource;
import io.vexil.wire.ConfigSnapshotDto;
import io.vexil.wire.VexilJson;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A {@link ConfigSource} persisted as a single JSON file, with mutation methods backing the
 * server's admin API.
 *
 * <p>Every mutation rewrites the file atomically (temp file + move) and notifies watchers with
 * the new snapshot — so a server using this source sees admin changes exactly the way it would
 * see changes from any other source, and a crash never leaves a half-written config behind.
 */
public final class JsonFileConfigSource implements ConfigStore {

    private final Path file;
    private final List<Consumer<ConfigSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private volatile ConfigSnapshot snapshot;

    public JsonFileConfigSource(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            this.snapshot = VexilJson.mapper()
                    .readValue(Files.readAllBytes(file), ConfigSnapshotDto.class)
                    .toSnapshot();
        } else {
            this.snapshot = new ConfigSnapshot(List.of(), List.of());
        }
    }

    @Override
    public ConfigSnapshot load() {
        return snapshot;
    }

    @Override
    public void watch(Consumer<ConfigSnapshot> listener) {
        listeners.add(listener);
    }

    @Override
    public synchronized void putExperiment(Experiment experiment) throws IOException {
        List<Experiment> updated = new ArrayList<>(
                snapshot.experiments().stream().filter(e -> !e.key().equals(experiment.key())).toList());
        updated.add(experiment);
        replace(new ConfigSnapshot(updated, snapshot.holdouts()));
    }

    @Override
    public synchronized boolean deleteExperiment(String key) throws IOException {
        List<Experiment> remaining =
                snapshot.experiments().stream().filter(e -> !e.key().equals(key)).toList();
        if (remaining.size() == snapshot.experiments().size()) {
            return false;
        }
        replace(new ConfigSnapshot(remaining, snapshot.holdouts()));
        return true;
    }

    /** Replaces the entire configuration (experiments and holdouts). */
    public synchronized void replace(ConfigSnapshot updated) throws IOException {
        persist(updated);
        this.snapshot = updated;
        for (Consumer<ConfigSnapshot> listener : listeners) {
            listener.accept(updated);
        }
    }

    private void persist(ConfigSnapshot updated) throws IOException {
        byte[] json = VexilJson.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(ConfigSnapshotDto.from(updated));
        Path directory = file.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        Files.write(temp, json);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
