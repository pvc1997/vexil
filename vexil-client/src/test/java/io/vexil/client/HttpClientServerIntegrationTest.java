package io.vexil.client;

import io.vexil.core.Assignment;
import io.vexil.core.EvaluationContext;
import io.vexil.core.engine.ExperimentEngine;
import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.model.Experiment;
import io.vexil.core.model.ExperimentStatus;
import io.vexil.core.model.Variant;
import io.vexil.core.source.InMemoryConfigSource;
import io.vexil.core.spi.ExposureEvent;
import io.vexil.server.VexilServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end: server on an ephemeral port, real HTTP client, SSE push, exposure round-trip. */
class HttpClientServerIntegrationTest {

    private InMemoryConfigSource serverSource;
    private VexilServer server;
    private ConcurrentLinkedQueue<ExposureEvent> serverReceivedEvents;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        serverSource = new InMemoryConfigSource(List.of(
                Experiment.running("exp", Variant.of("control", 1), Variant.of("treatment", 1))));
        serverReceivedEvents = new ConcurrentLinkedQueue<>();
        server = new VexilServer(0, serverSource, List.of(serverReceivedEvents::addAll));
        server.start();
        baseUrl = "http://localhost:" + server.port();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void loadFetchesCurrentSnapshot() {
        try (var source = new HttpConfigSource(baseUrl)) {
            ConfigSnapshot snapshot = source.load();
            assertEquals(1, snapshot.experiments().size());
            assertEquals("exp", snapshot.experiments().getFirst().key());
        }
    }

    @Test
    void watchReceivesSsePushOnConfigChange() throws Exception {
        try (var source = new HttpConfigSource(baseUrl)) {
            var latch = new CountDownLatch(1);
            var received = new AtomicReference<ConfigSnapshot>();
            source.watch(snapshot -> {
                if (snapshot.experiments().stream().anyMatch(e -> e.key().equals("new-exp"))) {
                    received.set(snapshot);
                    latch.countDown();
                }
            });
            Thread.sleep(300); // let the SSE stream connect before pushing the update
            serverSource.update(List.of(Experiment.running("new-exp", Variant.of("on", 1))));
            assertTrue(latch.await(10, TimeUnit.SECONDS), "SSE update never arrived");
            assertEquals("new-exp", received.get().experiments().getFirst().key());
        }
    }

    @Test
    void engineOverHttpReactsToRemoteConfigChanges() throws Exception {
        try (var source = new HttpConfigSource(baseUrl);
             var engine = new ExperimentEngine(source, List.of())) {
            assertTrue(engine.evaluateSilently("exp", EvaluationContext.of("u1")).enrolled());

            serverSource.update(List.of(new Experiment("exp", ExperimentStatus.PAUSED, null, 1.0,
                    List.of(Variant.of("control", 1)), List.of())));

            long deadline = System.currentTimeMillis() + 10_000;
            Assignment latest = null;
            while (System.currentTimeMillis() < deadline) {
                latest = engine.evaluateSilently("exp", EvaluationContext.of("u1"));
                if (!latest.enrolled()) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(Assignment.Reason.NOT_RUNNING, latest.reason(),
                    "engine never picked up the remote pause");
        }
    }

    @Test
    void exposuresFlowBackToServerThroughHttpEventSink() throws Exception {
        try (var source = new HttpConfigSource(baseUrl);
             var engine = new ExperimentEngine(source, List.of(new HttpEventSink(baseUrl)))) {
            engine.evaluate("exp", EvaluationContext.of("user-42"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (serverReceivedEvents.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        }
        assertEquals(1, serverReceivedEvents.size(), "exposure never reached the server");
        assertEquals("user-42", serverReceivedEvents.peek().unitId());
        assertEquals("exp", serverReceivedEvents.peek().experimentKey());
    }
}
