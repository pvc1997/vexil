package io.vexil.client;

import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.spi.ConfigSource;
import io.vexil.wire.ConfigSnapshotDto;
import io.vexil.wire.VexilJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * SDK-side {@link ConfigSource} backed by a vexil-server instance.
 *
 * <p>{@link #load()} fetches the current snapshot over HTTP. {@link #watch(Consumer)} runs a
 * dedicated virtual thread that holds a Server-Sent Events stream open against
 * {@code /api/stream}; the server pushes the full snapshot on connect and on every change. On
 * connection loss the watcher reconnects with exponential backoff, and each retry first fetches
 * a fresh snapshot over plain HTTP so config stays current even while the stream is down.
 */
public final class HttpConfigSource implements ConfigSource {

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final URI snapshotUri;
    private final URI streamUri;
    private final HttpClient httpClient;
    private final List<Consumer<ConfigSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private volatile Thread watcher;
    private volatile boolean closed;

    public HttpConfigSource(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.snapshotUri = URI.create(base + "/api/experiments");
        this.streamUri = URI.create(base + "/api/stream");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ConfigSnapshot load() {
        try {
            return fetchSnapshot();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load config from " + snapshotUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while loading config", e);
        }
    }

    @Override
    public synchronized void watch(Consumer<ConfigSnapshot> listener) {
        listeners.add(listener);
        if (watcher == null) {
            watcher = Thread.ofVirtual().name("vexil-config-watcher").start(this::watchLoop);
        }
    }

    private void watchLoop() {
        Duration backoff = INITIAL_BACKOFF;
        while (!closed) {
            try {
                streamOnce();
                backoff = INITIAL_BACKOFF; // the stream was up; start fresh after a clean drop
            } catch (IOException | java.io.UncheckedIOException e) {
                // the line iterator wraps read failures (incl. interrupt on close) unchecked;
                // fall through to poll + backoff
            } catch (InterruptedException e) {
                return;
            }
            if (closed) {
                return;
            }
            try {
                publish(fetchSnapshot()); // polling fallback keeps config fresh while SSE is down
            } catch (IOException e) {
                // server fully unreachable; keep the last known config
            } catch (InterruptedException e) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
        }
    }

    /** Holds one SSE connection open, publishing every config event, until the stream drops. */
    private void streamOnce() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(streamUri)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<Stream<String>> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            throw new IOException("SSE endpoint returned " + response.statusCode());
        }
        StringBuilder data = new StringBuilder();
        for (var iterator = response.body().iterator(); iterator.hasNext() && !closed; ) {
            String line = iterator.next();
            if (line.startsWith("data:")) {
                data.append(line.substring(5).stripLeading());
            } else if (line.isEmpty() && !data.isEmpty()) {
                parseAndPublish(data.toString());
                data.setLength(0);
            }
            // "event:" and ": keep-alive" lines need no handling
        }
    }

    private void parseAndPublish(String json) {
        try {
            publish(VexilJson.mapper().readValue(json, ConfigSnapshotDto.class).toSnapshot());
        } catch (IOException | RuntimeException e) {
            // a malformed event must not kill the watcher; the next event or poll recovers
        }
    }

    private ConfigSnapshot fetchSnapshot() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(snapshotUri).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("config endpoint returned " + response.statusCode());
        }
        return VexilJson.mapper().readValue(response.body(), ConfigSnapshotDto.class).toSnapshot();
    }

    private void publish(ConfigSnapshot snapshot) {
        for (Consumer<ConfigSnapshot> listener : listeners) {
            listener.accept(snapshot);
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread current = watcher;
        if (current != null) {
            current.interrupt();
        }
        httpClient.close();
    }
}
