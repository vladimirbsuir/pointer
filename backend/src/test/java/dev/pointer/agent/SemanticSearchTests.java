package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.testing.GatewayTestClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

class SemanticSearchTests extends AgentIntegrationTest {

    private static final String SESSIONS_FILE = "src/sessions.ts";
    private static final String BILLING_FILE = "src/billing.ts";
    private static final String THEME_FILE = "src/theme.ts";

    @Test
    void indexingRequiresAnEmbeddingModel() {
        var setup = setUp(false);

        var error = client.post()
                .uri("/api/v1/projects/{id}/index", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST)
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        assertThat(error.toString()).contains("embedding_model_not_configured");
    }

    @Test
    void projectIsIndexedAndSearchPicksUpChangedFiles() throws Exception {
        var setup = setUp(false);
        useEmbeddingModel(setup);
        var files = new ConcurrentHashMap<String, String>(Map.of(
                SESSIONS_FILE, "export function createSession(project) { return open session for project }",
                BILLING_FILE, "export function chargeInvoice(card) { return payment charge for invoice }"));
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(setup, files, requests)) {
            client.post()
                    .uri("/api/v1/projects/{id}/index", setup.projectId())
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.ACCEPTED);
            var status = awaitIndexStatus(setup, "ready");
            assertThat(status.path("fileCount").asInt()).isEqualTo(2);
            assertThat(status.path("chunkCount").asInt()).isEqualTo(2);
            assertThat(status.path("embeddingModelKey").asString()).isEqualTo("fake-embed");

            files.remove(BILLING_FILE);
            files.put(THEME_FILE, "export const theme = { color: dark session colors }");
            llm.enqueueToolCall("call_1", "semantic_search", "{\"query\":\"create session for a project\"}");
            llm.enqueueText("Sessions are created in sessions.ts.");
            send(setup, "Where are sessions created?", null);
            device.awaitEvent("turn.completed", TIMEOUT);

            var toolItem = completedItem(device);
            assertThat(toolItem.path("toolStatus").asString()).isEqualTo("succeeded");
            var results = toolItem.path("result").path("results");
            assertThat(results.get(0).path("path").asString()).isEqualTo(SESSIONS_FILE);
            assertThat(results.get(0).path("symbol").asString()).isEqualTo("createSession");
            assertThat(results.toString()).doesNotContain(BILLING_FILE).contains(THEME_FILE);
            assertThat(toolItem.path("result").path("index").asString()).isEqualTo("fresh");
        }

        assertThat(llm.requests().getFirst()).contains("semantic_search");
        var chunkRequests = requests.stream()
                .filter(request -> request.path("operation").asString().equals("pointer.index_chunks"))
                .map(request -> request.path("arguments").path("paths").toString())
                .toList();
        assertThat(chunkRequests).hasSize(2);
        assertThat(chunkRequests.get(1)).contains(THEME_FILE).doesNotContain(SESSIONS_FILE);
        var status = indexStatus(setup);
        assertThat(status.path("fileCount").asInt()).isEqualTo(2);
    }

    @Test
    void searchToolIsHiddenUntilTheProjectIsIndexed() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("No index yet.");

        try (var device = executor(setup, new ConcurrentHashMap<>(), new CopyOnWriteArrayList<>())) {
            send(setup, "Hello", null);
            device.awaitEvent("turn.completed", TIMEOUT);
        }

        assertThat(llm.requests().getFirst()).contains("read_file").doesNotContain("semantic_search");
    }

    private void useEmbeddingModel(Setup setup) {
        var providers = client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(ProviderDtos.ProviderResponse[].class)
                .returnResult()
                .getResponseBody();
        var model = client.post()
                .uri("/api/v1/providers/{id}/models", providers[0].id())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelKey", "fake-embed"))
                .exchange()
                .expectBody(ProviderDtos.ModelResponse.class)
                .returnResult()
                .getResponseBody();
        client.put()
                .uri("/api/v1/me/settings")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("embeddingModelId", model.id().toString()))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private JsonNode awaitIndexStatus(Setup setup, String expected) throws InterruptedException {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        var status = indexStatus(setup);
        while (!status.path("status").asString().equals(expected) && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(100));
            status = indexStatus(setup);
        }
        assertThat(status.path("status").asString()).isEqualTo(expected);
        return status;
    }

    private JsonNode indexStatus(Setup setup) {
        return client.get()
                .uri("/api/v1/projects/{id}/index", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private GatewayTestClient executor(Setup setup, Map<String, String> files, List<JsonNode> requests)
            throws Exception {
        var device = GatewayTestClient.connect(
                port,
                setup.auth().accessToken(),
                setup.deviceId(),
                List.of("viewer", "executor"),
                List.of(setup.projectId().toString()));
        device.onMessage(message -> {
            if (type(message).equals("exec.request")) {
                var request = message.path("payload");
                requests.add(request);
                var response =
                        new HashMap<String, Object>(Map.of("status", "succeeded", "result", respond(request, files)));
                response.put("callId", request.path("callId").asString());
                device.send("exec.result", response);
            }
        });
        device.subscribe(setup.sessionId(), 0);
        return device;
    }

    private static Map<String, Object> respond(JsonNode request, Map<String, String> files) {
        return switch (request.path("operation").asString()) {
            case "pointer.index_scan" ->
                Map.of(
                        "files",
                        files.entrySet().stream()
                                .map(file -> Map.of("path", file.getKey(), "hash", hash(file.getValue())))
                                .toList(),
                        "truncated",
                        false);
            case "pointer.index_chunks" -> {
                var results = new ArrayList<Map<String, Object>>();
                request.path("arguments").path("paths").forEach(path -> {
                    var content = files.get(path.asString());
                    results.add(
                            content == null
                                    ? Map.of("path", path.asString(), "missing", true)
                                    : Map.of(
                                            "path", path.asString(),
                                            "hash", hash(content),
                                            "chunks", List.of(chunk(content))));
                });
                yield Map.of("files", results);
            }
            default -> Map.of();
        };
    }

    private static Map<String, Object> chunk(String content) {
        var symbol = content.replaceAll("^export (function|const) (\\w+).*$", "$2");
        return Map.of(
                "symbol", symbol, "kind", "function_declaration", "startLine", 1, "endLine", 1, "content", content);
    }

    private static String hash(String content) {
        return Integer.toHexString(content.hashCode());
    }

    private static JsonNode completedItem(GatewayTestClient device) {
        return device.events().stream()
                .filter(event -> eventKind(event).equals("item.completed"))
                .map(event -> event.path("payload").path("event").path("item"))
                .filter(item -> item.path("type").asString().equals("tool_call"))
                .findFirst()
                .orElseThrow();
    }
}
