package io.vexil.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.spi.ConfigSource;
import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;
import io.vexil.wire.ConfigSnapshotDto;
import io.vexil.wire.VexilJson;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Self-hostable config-delivery and exposure-ingestion server.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/experiments} — current config snapshot (experiments + holdouts) as JSON</li>
 *   <li>{@code PUT /api/experiments/{key}} — create or replace an experiment (admin; requires a
 *       {@link ConfigStore}-backed source and, when configured, a bearer token)</li>
 *   <li>{@code DELETE /api/experiments/{key}} — delete an experiment (admin, same requirements)</li>
 *   <li>{@code GET /api/stream} — Server-Sent Events; pushes the full snapshot on connect and
 *       again on every config change, so SDKs pick up changes in near real time</li>
 *   <li>{@code POST /api/events} — accepts a JSON array of exposure events from SDKs and
 *       forwards them to the configured {@link EventSink}s</li>
 * </ul>
 *
 * <p>Every request — including each long-lived SSE stream — runs on its own virtual thread, so
 * plain blocking writes scale to tens of thousands of concurrently connected SDK clients without
 * a reactive framework.
 */
public final class VexilServer implements AutoCloseable {

    private final HttpServer httpServer;
    private final ObjectMapper mapper = VexilJson.mapper();
    private final List<EventSink> sinks;
    private final List<SynchronousQueue<String>> streamClients = new CopyOnWriteArrayList<>();
    private final ConfigStore store; // null when the config source is read-only
    private final String adminToken; // null disables auth
    private volatile ConfigSnapshot snapshot;

    public VexilServer(int port, ConfigSource configSource, List<EventSink> sinks) throws IOException {
        this(port, configSource, sinks, null);
    }

    /**
     * @param adminToken when non-null, mutating admin requests must carry
     *                   {@code Authorization: Bearer <adminToken>}
     */
    public VexilServer(int port, ConfigSource configSource, List<EventSink> sinks, String adminToken)
            throws IOException {
        this.sinks = List.copyOf(sinks);
        this.store = configSource instanceof ConfigStore mutable ? mutable : null;
        this.adminToken = adminToken;
        this.snapshot = configSource.load();
        configSource.watch(this::onConfigChanged);

        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.createContext("/api/experiments", this::handleExperiments);
        httpServer.createContext("/api/stream", this::handleStream);
        httpServer.createContext("/api/events", this::handleEvents);
    }

    public void start() {
        httpServer.start();
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    private void onConfigChanged(ConfigSnapshot updated) {
        this.snapshot = updated;
        String payload;
        try {
            payload = mapper.writeValueAsString(ConfigSnapshotDto.from(updated));
        } catch (IOException e) {
            return;
        }
        for (SynchronousQueue<String> client : streamClients) {
            client.offer(payload); // slow consumers skip this update and catch up on the next
        }
    }

    private void handleExperiments(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String key = path.length() > "/api/experiments/".length()
                ? path.substring("/api/experiments/".length())
                : null;
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                byte[] body = mapper.writeValueAsBytes(ConfigSnapshotDto.from(snapshot));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            case "PUT" -> handleAdminPut(exchange, key);
            case "DELETE" -> handleAdminDelete(exchange, key);
            default -> exchange.sendResponseHeaders(405, -1);
        }
    }

    /** Returns true when the request may mutate config; otherwise responds and returns false. */
    private boolean authorizeAdmin(HttpExchange exchange, String key) throws IOException {
        if (key == null || key.isBlank()) {
            exchange.sendResponseHeaders(400, -1);
            return false;
        }
        if (store == null) {
            exchange.sendResponseHeaders(403, -1); // read-only config source
            return false;
        }
        if (adminToken != null) {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + adminToken).equals(header)) {
                exchange.sendResponseHeaders(401, -1);
                return false;
            }
        }
        return true;
    }

    private void handleAdminPut(HttpExchange exchange, String key) throws IOException {
        if (!authorizeAdmin(exchange, key)) {
            return;
        }
        try {
            var experiment = mapper
                    .readValue(exchange.getRequestBody(), ConfigSnapshotDto.ExperimentDto.class)
                    .toExperiment();
            if (!experiment.key().equals(key)) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            store.putExperiment(experiment);
            exchange.sendResponseHeaders(204, -1);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            exchange.sendResponseHeaders(400, -1);
        }
    }

    private void handleAdminDelete(HttpExchange exchange, String key) throws IOException {
        if (!authorizeAdmin(exchange, key)) {
            return;
        }
        exchange.sendResponseHeaders(store.deleteExperiment(key) ? 204 : 404, -1);
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);

        SynchronousQueue<String> updates = new SynchronousQueue<>();
        streamClients.add(updates);
        try (OutputStream out = exchange.getResponseBody()) {
            writeSseEvent(out, mapper.writeValueAsString(ConfigSnapshotDto.from(snapshot)));
            while (!Thread.currentThread().isInterrupted()) {
                String next = updates.poll(15, TimeUnit.SECONDS);
                if (next == null) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else {
                    writeSseEvent(out, next);
                }
            }
        } catch (IOException | InterruptedException e) {
            // client disconnected or server shutting down — either way, just clean up
        } finally {
            streamClients.remove(updates);
        }
    }

    private static void writeSseEvent(OutputStream out, String json) throws IOException {
        out.write(("event: config\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        ExposureEvent[] events;
        try {
            events = mapper.readValue(exchange.getRequestBody(), ExposureEvent[].class);
        } catch (IOException e) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        List<ExposureEvent> batch = List.of(events);
        for (EventSink sink : sinks) {
            try {
                sink.accept(batch);
            } catch (RuntimeException e) {
                // one failing sink must not reject the ingest for the others
            }
        }
        exchange.sendResponseHeaders(202, -1);
    }

    @Override
    public void close() {
        httpServer.stop(1);
        for (EventSink sink : sinks) {
            try {
                sink.close();
            } catch (Exception e) {
                // best effort on shutdown
            }
        }
    }
}
