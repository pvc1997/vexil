package io.vexil.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.vexil.core.model.Experiment;
import io.vexil.core.spi.ConfigSource;
import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;

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
 *   <li>{@code GET /api/experiments} — current experiment set as JSON</li>
 *   <li>{@code GET /api/stream} — Server-Sent Events; pushes the full experiment set on connect
 *       and again on every config change, so SDKs pick up changes in near real time</li>
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
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<EventSink> sinks;
    private final List<SynchronousQueue<String>> streamClients = new CopyOnWriteArrayList<>();
    private volatile List<Experiment> experiments;

    public VexilServer(int port, ConfigSource configSource, List<EventSink> sinks) throws IOException {
        this.sinks = List.copyOf(sinks);
        this.experiments = configSource.load();
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

    private void onConfigChanged(List<Experiment> updated) {
        this.experiments = updated;
        String payload;
        try {
            payload = mapper.writeValueAsString(ExperimentDto.from(updated));
        } catch (IOException e) {
            return;
        }
        for (SynchronousQueue<String> client : streamClients) {
            client.offer(payload); // slow consumers skip this update and catch up on the next
        }
    }

    private void handleExperiments(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = mapper.writeValueAsBytes(ExperimentDto.from(experiments));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
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
            writeSseEvent(out, mapper.writeValueAsString(ExperimentDto.from(experiments)));
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
