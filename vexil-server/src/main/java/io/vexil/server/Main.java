package io.vexil.server;

import io.vexil.core.model.Experiment;
import io.vexil.core.model.Variant;
import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point: {@code java ... io.vexil.server.Main [port] [configFile]}.
 *
 * <p>Experiments persist in a JSON file (default {@code ./vexil-config.json}), editable through
 * the admin API or directly on disk. Set {@code VEXIL_ADMIN_TOKEN} to require a bearer token on
 * mutating requests. Ingested exposures are logged to stdout — swap in a JDBC or Kafka sink for
 * real deployments.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path configFile = Path.of(args.length > 1 ? args[1] : "vexil-config.json");

        boolean firstRun = !Files.exists(configFile);
        var store = new JsonFileConfigSource(configFile);
        if (firstRun) {
            store.putExperiment(Experiment.running("sample-checkout-cta",
                    Variant.of("control", 1),
                    Variant.of("treatment", 1)));
        }

        EventSink stdoutSink = batch -> {
            for (ExposureEvent event : batch) {
                System.out.printf("exposure: %s -> %s (unit %s)%n",
                        event.experimentKey(), event.variantKey(), event.unitId());
            }
        };

        String adminToken = System.getenv("VEXIL_ADMIN_TOKEN");
        var server = new VexilServer(port, store, List.of(stdoutSink), adminToken);
        server.start();
        System.out.println("Vexil server listening on http://localhost:" + server.port()
                + " (config: " + configFile.toAbsolutePath() + ")");
        System.out.println("  GET    /api/experiments         current config");
        System.out.println("  PUT    /api/experiments/{key}   create/replace experiment (admin)");
        System.out.println("  DELETE /api/experiments/{key}   delete experiment (admin)");
        System.out.println("  GET    /api/stream              SSE config stream");
        System.out.println("  POST   /api/events              exposure ingestion");
        if (adminToken == null) {
            System.out.println("warning: VEXIL_ADMIN_TOKEN not set — admin API is unauthenticated");
        }
        Thread.currentThread().join();
    }
}
