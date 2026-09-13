package io.vexil.client;

import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;
import io.vexil.wire.VexilJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Forwards exposure batches to a vexil-server instance's {@code /api/events} endpoint.
 *
 * <p>The engine already invokes sinks in batches from a dedicated virtual thread, so this sink
 * simply performs one blocking POST per batch. Failed posts are dropped — exposure delivery is
 * best-effort by design; an analytics outage must never affect serving.
 */
public final class HttpEventSink implements EventSink {

    private final URI eventsUri;
    private final HttpClient httpClient;

    public HttpEventSink(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.eventsUri = URI.create(base + "/api/events");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void accept(List<ExposureEvent> batch) {
        try {
            HttpRequest request = HttpRequest.newBuilder(eventsUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            VexilJson.mapper().writeValueAsBytes(batch)))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // best effort — the batch is dropped
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
