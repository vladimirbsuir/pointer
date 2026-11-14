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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class SemanticSearchTests extends AgentIntegrationTest {

    private static final String SESSIONS_FILE = "src/sessions.ts";
    private static final String BILLING_FILE = "src/billing.ts";
    private static final String THEME_FILE = "src/theme.ts";
    private static final String CRYPTO_FILE = "src/crypto.ts";
    private static final String VAULT_FILE = "src/vault.ts";

    @Autowired
    private JdbcClient jdbc;

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
        useEmbeddingModel(setup, "fake-embed");
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
    void prefixesOfTheModelAreAppliedAndChangingThemRebuildsTheIndex() throws Exception {
        var setup = setUp(false);
        var model = useEmbeddingModel(setup, "nomic-embed-text:latest");
        assertThat(model.embeddingQueryPrefix()).isEqualTo("search_query: ");
        assertThat(model.embeddingDocumentPrefix()).isEqualTo("search_document: ");
        var files = new ConcurrentHashMap<String, String>(Map.of(
                SESSIONS_FILE, "export function createSession(project) { return open session for project }",
                BILLING_FILE, "export function chargeInvoice(card) { return payment charge for invoice }"));
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(setup, files, requests)) {
            startIndex(setup);
            awaitIndexStatus(setup, "ready");
            assertThat(llm.embeddingInputs().stream().flatMap(List::stream))
                    .isNotEmpty()
                    .allMatch(input -> input.startsWith("search_document: "));

            llm.enqueueToolCall("call_1", "semantic_search", "{\"query\":\"create session\"}");
            llm.enqueueText("Found it.");
            send(setup, "Where are sessions created?", null);
            device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(llm.embeddingInputs().getLast()).containsExactly("search_query: create session");

            client.patch()
                    .uri("/api/v1/providers/{providerId}/models/{modelId}", model.providerId(), model.id())
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .body(Map.of("embeddingDocumentPrefix", ""))
                    .exchange()
                    .expectStatus()
                    .isOk();
            assertThat(indexStatus(setup).path("prefixesChanged").asBoolean()).isTrue();

            var embeddingCalls = llm.embeddingInputs().size();
            startIndex(setup);
            var status = awaitIndexStatus(setup, "ready");
            assertThat(status.path("prefixesChanged").asBoolean()).isFalse();
            var rebuilt = llm
                    .embeddingInputs()
                    .subList(embeddingCalls, llm.embeddingInputs().size())
                    .stream()
                    .flatMap(List::stream)
                    .toList();
            assertThat(rebuilt).hasSize(2).noneMatch(input -> input.startsWith("search_document: "));
        }
    }

    @Test
    void hybridSearchFindsExactNamesThatVectorSearchMissesAndRebuildsOutdatedTokens() throws Exception {
        var setup = setUp(false);
        useEmbeddingModel(setup, "fake-embed");
        var files = new ConcurrentHashMap<String, String>(Map.of(
                CRYPTO_FILE, "export const SecretCipher = createCipher(masterKey)",
                VAULT_FILE, "export function whereIsTheCode() { return where is the code }"));
        var search = "{\"query\":\"where is the code for secret cipher\",\"limit\":1}";

        try (var device = executor(setup, files, new CopyOnWriteArrayList<>())) {
            startIndex(setup);
            assertThat(awaitIndexStatus(setup, "ready").path("searchStrategy").asString())
                    .isEqualTo("hybrid");

            llm.enqueueToolCall("call_1", "semantic_search", search);
            llm.enqueueText("Found.");
            send(setup, "Find the cipher", null);
            awaitTurns(device, 1);
            awaitTitle(device);
            assertThat(firstResultPath(device, 0)).isEqualTo(CRYPTO_FILE);

            assertThat(changeStrategy(setup, "vector").path("searchStrategy").asString())
                    .isEqualTo("vector");
            llm.enqueueToolCall("call_2", "semantic_search", search);
            llm.enqueueText("Found.");
            send(setup, "Find it by meaning only", null);
            awaitTurns(device, 2);
            assertThat(firstResultPath(device, 1)).isEqualTo(VAULT_FILE);

            changeStrategy(setup, "hybrid");
            jdbc.sql("update code_chunks set lexical = null where project_id = :projectId")
                    .param("projectId", setup.projectId())
                    .update();
            jdbc.sql("update index_states set lexical_version = 0 where project_id = :projectId")
                    .param("projectId", setup.projectId())
                    .update();
            llm.enqueueToolCall("call_3", "semantic_search", search);
            llm.enqueueText("Found.");
            send(setup, "Find it again", null);
            awaitTurns(device, 3);
            assertThat(firstResultPath(device, 2)).isEqualTo(CRYPTO_FILE);
        }

        var version = jdbc.sql("select lexical_version from index_states where project_id = :projectId")
                .param("projectId", setup.projectId())
                .query(Integer.class)
                .single();
        assertThat(version).isEqualTo(1);
        var lexical = query(setup, "lexical");
        assertThat(lexical.path("strategy").asString()).isEqualTo("lexical");
        assertThat(lexical.path("results")).hasSize(1);
        assertThat(lexical.path("results").get(0).path("path").asString()).isEqualTo(CRYPTO_FILE);
        assertThat(lexical.path("results").get(0).path("lexicalRank").asInt()).isEqualTo(1);
        assertThat(lexical.path("results").get(0).path("vectorRank").isNull()).isTrue();
        var hybrid = query(setup, "hybrid");
        assertThat(hybrid.path("results").get(0).path("path").asString()).isEqualTo(CRYPTO_FILE);
        assertThat(hybrid.path("results")).hasSize(2);
        assertThat(query(setup, "vector").path("results").get(0).path("path").asString())
                .isEqualTo(VAULT_FILE);
        client.put()
                .uri("/api/v1/projects/{id}/index/settings", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("searchStrategy", "lexical"))
                .exchange()
                .expectStatus()
                .isBadRequest();
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

    private JsonNode changeStrategy(Setup setup, String strategy) {
        return client.put()
                .uri("/api/v1/projects/{id}/index/settings", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("searchStrategy", strategy))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode query(Setup setup, String strategy) {
        return client.post()
                .uri("/api/v1/projects/{id}/index/query", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("query", "where is the code for secret cipher", "strategy", strategy, "limit", 5))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private static void awaitTurns(GatewayTestClient device, int count) throws Exception {
        device.await(
                message -> device.events().stream()
                                .filter(event -> eventKind(event).equals("turn.completed"))
                                .count()
                        >= count,
                TIMEOUT);
    }

    private static void awaitTitle(GatewayTestClient device) throws Exception {
        device.await(
                message -> eventKind(message).equals("session.updated")
                        && message.path("payload")
                                .path("event")
                                .path("title")
                                .asString()
                                .equals("Title"),
                TIMEOUT);
    }

    private static String firstResultPath(GatewayTestClient device, int callIndex) {
        return device.events().stream()
                .filter(event -> eventKind(event).equals("item.completed"))
                .map(event -> event.path("payload").path("event").path("item"))
                .filter(item -> item.path("type").asString().equals("tool_call"))
                .toList()
                .get(callIndex)
                .path("result")
                .path("results")
                .get(0)
                .path("path")
                .asString();
    }

    private void startIndex(Setup setup) {
        client.post()
                .uri("/api/v1/projects/{id}/index", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    private ProviderDtos.ModelResponse useEmbeddingModel(Setup setup, String modelKey) {
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
                .body(Map.of("modelKey", modelKey))
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
        return model;
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
