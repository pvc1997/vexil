package io.vexil.server;

import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.model.Experiment;
import io.vexil.core.model.Variant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileConfigSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void mutationsPersistAcrossReopen() throws Exception {
        Path file = tempDir.resolve("config.json");

        var store = new JsonFileConfigSource(file);
        store.putExperiment(Experiment.running("a", Variant.of("on", 1)));
        store.putExperiment(Experiment.running("b", Variant.of("on", 1)));
        store.deleteExperiment("a");

        var reopened = new JsonFileConfigSource(file);
        ConfigSnapshot snapshot = reopened.load();
        assertEquals(1, snapshot.experiments().size());
        assertEquals("b", snapshot.experiments().getFirst().key());
    }

    @Test
    void startsEmptyWhenFileMissing() throws Exception {
        var store = new JsonFileConfigSource(tempDir.resolve("missing.json"));
        assertEquals(List.of(), store.load().experiments());
    }

    @Test
    void notifiesWatchersOnMutation() throws Exception {
        var store = new JsonFileConfigSource(tempDir.resolve("config.json"));
        var seen = new AtomicReference<ConfigSnapshot>();
        store.watch(seen::set);

        store.putExperiment(Experiment.running("watched", Variant.of("on", 1)));

        assertTrue(seen.get().experiments().stream().anyMatch(e -> e.key().equals("watched")));
    }

    @Test
    void putReplacesExperimentWithSameKey() throws Exception {
        var store = new JsonFileConfigSource(tempDir.resolve("config.json"));
        store.putExperiment(Experiment.running("exp", Variant.of("v1", 1)));
        store.putExperiment(Experiment.running("exp", Variant.of("v2", 1)));

        var experiments = store.load().experiments();
        assertEquals(1, experiments.size());
        assertEquals("v2", experiments.getFirst().variants().getFirst().key());
    }
}
