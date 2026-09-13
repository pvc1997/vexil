package io.vexil.server;

import io.vexil.core.model.Experiment;
import io.vexil.core.model.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminApiTest {

    private static final String TOKEN = "secret-token";

    @TempDir
    Path tempDir;

    private JsonFileConfigSource store;
    private VexilServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        store = new JsonFileConfigSource(tempDir.resolve("config.json"));
        store.putExperiment(Experiment.running("existing", Variant.of("on", 1)));
        server = new VexilServer(0, store, List.of(), TOKEN);
        server.start();
        client = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + server.port();
    }

    @AfterEach
    void stopServer() {
        server.close();
        client.close();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static final String NEW_EXPERIMENT_JSON = """
            {"key":"new-exp","status":"RUNNING","salt":"new-exp","trafficAllocation":1.0,
             "variants":[{"key":"control","weight":1.0,"payload":{}},
                         {"key":"treatment","weight":1.0,"payload":{}}],
             "targetingRules":[]}
            """;

    @Test
    void putCreatesExperimentAndGetReflectsIt() throws Exception {
        var put = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/experiments/new-exp"))
                .header("Authorization", "Bearer " + TOKEN)
                .PUT(HttpRequest.BodyPublishers.ofString(NEW_EXPERIMENT_JSON))
                .build());
        assertEquals(204, put.statusCode());

        var get = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/experiments")).GET().build());
        assertTrue(get.body().contains("\"new-exp\""),
                "GET must reflect the admin PUT (via the watch pipeline)");
    }

    @Test
    void deleteRemovesExperiment() throws Exception {
        var delete = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/experiments/existing"))
                .header("Authorization", "Bearer " + TOKEN)
                .DELETE()
                .build());
        assertEquals(204, delete.statusCode());

        var get = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/experiments")).GET().build());
        assertFalse(get.body().contains("\"existing\""));

        var deleteAgain = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/experiments/existing"))
                .header("Authorization", "Bearer " + TOKEN)
                .DELETE()
                .build());
        assertEquals(404, deleteAgain.statusCode());
    }

    @Test
    void mutationsRequireBearerToken() throws Exception {
        var noToken = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/experiments/new-exp"))
                .PUT(HttpRequest.BodyPublishers.ofString(NEW_EXPERIMENT_JSON))
                .build());
        assertEquals(401, noToken.statusCode());

        var wrongToken = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/experiments/existing"))
                .header("Authorization", "Bearer wrong")
                .DELETE()
                .build());
        assertEquals(401, wrongToken.statusCode());
    }

    @Test
    void rejectsInvalidBodies() throws Exception {
        var malformed = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/experiments/new-exp"))
                .header("Authorization", "Bearer " + TOKEN)
                .PUT(HttpRequest.BodyPublishers.ofString("{not json"))
                .build());
        assertEquals(400, malformed.statusCode());

        var keyMismatch = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/experiments/other-key"))
                .header("Authorization", "Bearer " + TOKEN)
                .PUT(HttpRequest.BodyPublishers.ofString(NEW_EXPERIMENT_JSON))
                .build());
        assertEquals(400, keyMismatch.statusCode());
    }
}
