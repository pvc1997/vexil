package io.vexil.server;

import io.vexil.core.model.Experiment;
import io.vexil.core.model.Variant;
import io.vexil.core.source.InMemoryConfigSource;
import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;

import java.util.List;

/** Demo entry point: serves one sample experiment and logs ingested exposures to stdout. */
public final class Main {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        var configSource = new InMemoryConfigSource(List.of(
                Experiment.running("sample-checkout-cta",
                        Variant.of("control", 1),
                        Variant.of("treatment", 1))));

        EventSink stdoutSink = batch -> {
            for (ExposureEvent event : batch) {
                System.out.printf("exposure: %s -> %s (unit %s)%n",
                        event.experimentKey(), event.variantKey(), event.unitId());
            }
        };

        var server = new VexilServer(port, configSource, List.of(stdoutSink));
        server.start();
        System.out.println("Vexil server listening on http://localhost:" + server.port());
        System.out.println("  GET  /api/experiments   current config");
        System.out.println("  GET  /api/stream        SSE config stream");
        System.out.println("  POST /api/events        exposure ingestion");
        Thread.currentThread().join();
    }
}
