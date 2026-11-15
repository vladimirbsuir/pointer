package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ExploreTests extends AgentIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String EXPLORE_CALL = "{\"task\":\"Find where the greeting is defined\",\"paths\":[\"src\"]}";

    @Test
    void exploreRunsAReadOnlySubAgentOnTheFastModelAndReturnsItsReport() throws Exception {
        var setup = setUp(false);
        enableExplore(setup);
        llm.enqueueToolCall("call_main", "explore", EXPLORE_CALL);
        llm.enqueueToolCall("call_sub", "grep", "{\"pattern\":\"greeting\"}");
        llm.enqueueText("REPORT: the greeting is defined in src/a.ts:3.");
        llm.enqueueText("It lives in src/a.ts.");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(setup, requests, Map.of("matches", "RAW-GREP-OUTPUT"))) {
            send(setup, "Where is the greeting?", null);
            var completed = device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("completed");
        }

        var sent = llm.requests();
        assertThat(model(sent.get(0))).isEqualTo("fake-model");
        assertThat(toolNames(sent.get(0))).contains("explore", "write_file");
        assertThat(model(sent.get(1))).isEqualTo("fast-model");
        assertThat(toolNames(sent.get(1))).containsExactlyInAnyOrder("list_directory", "read_file", "glob", "grep");
        assertThat(sent.get(1))
                .contains("read-only investigator")
                .contains("Find where the greeting is defined")
                .contains("Start from:\\n- src")
                .doesNotContain("Where is the greeting?");
        assertThat(model(sent.get(2))).isEqualTo("fast-model");
        assertThat(sent.get(2)).contains("RAW-GREP-OUTPUT").contains("\"tool_call_id\":\"call_sub\"");
        assertThat(model(sent.get(3))).isEqualTo("fake-model");
        assertThat(sent.get(3))
                .contains("REPORT: the greeting is defined in src/a.ts:3.")
                .contains("\"tool_call_id\":\"call_main\"")
                .doesNotContain("RAW-GREP-OUTPUT")
                .doesNotContain("call_sub");

        var items = items(setup);
        var explore = toolCall(items, "explore");
        assertThat(explore.path("toolStatus").asString()).isEqualTo("succeeded");
        assertThat(explore.has("parentItemId")).isFalse();
        assertThat(explore.path("result").path("steps").asInt()).isEqualTo(2);
        var nested = toolCall(items, "grep");
        assertThat(nested.path("parentItemId").asString())
                .isEqualTo(explore.path("id").asString());
        assertThat(nested.path("toolStatus").asString()).isEqualTo("succeeded");
        assertThat(requests.stream().map(request -> request.path("operation").asString()))
                .contains("grep");
        assertThat(usage(setup).entries())
                .filteredOn(entry -> entry.purpose().equals("explore"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.modelLabel()).isEqualTo("fast-model");
                    assertThat(entry.role()).isEqualTo("explore");
                    assertThat(entry.calls()).isEqualTo(1);
                });
    }

    @Test
    void exploreIsNotOfferedWithoutSmartRoutingOrAFastModelWithTools() throws Exception {
        var setup = setUp(false);
        var fast = enableExplore(setup);
        updateSession(setup, Map.of("routing", "off"));
        llm.enqueueText("First.");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), Map.of())) {
            send(setup, "Hello", null);
            device.awaitEvent("turn.completed", TIMEOUT);
            device.await(message -> llm.requests().size() >= 2, TIMEOUT);

            updateSession(setup, Map.of("routing", "smart"));
            client.patch()
                    .uri(
                            "/api/v1/providers/{providerId}/models/{modelId}",
                            provider(setup).id(),
                            fast)
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .body(Map.of("supportsTools", false))
                    .exchange()
                    .expectStatus()
                    .isOk();
            llm.enqueueText("Second.");
            send(setup, "Hello again", null);
            device.await(message -> llm.requests().size() >= 3, TIMEOUT);
        }

        assertThat(toolNames(llm.requests().get(0))).contains("read_file").doesNotContain("explore");
        assertThat(toolNames(llm.requests().get(2))).contains("read_file").doesNotContain("explore");
    }

    @Test
    void subAgentCannotUseOtherToolsAndIsCutOffAtTheStepLimitWithATruncatedReport() throws Exception {
        var setup = setUp(false);
        enableExplore(setup);
        llm.enqueueToolCall("call_main", "explore", EXPLORE_CALL);
        llm.enqueueToolCall("call_write", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");
        for (var step = 1; step < ExploreAgent.MAX_STEPS - 1; step++) {
            llm.enqueueToolCall("call_" + step, "read_file", "{\"path\":\"a.txt\"}");
        }
        llm.enqueueText("R".repeat(ExploreAgent.MAX_REPORT_CHARS + 500));
        llm.enqueueText("Done.");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(setup, requests, Map.of("content", "x"))) {
            send(setup, "Explore", null);
            device.awaitEvent("turn.completed", TIMEOUT);
        }

        var sent = llm.requests();
        var lastSubAgentRequest = sent.get(ExploreAgent.MAX_STEPS);
        assertThat(model(lastSubAgentRequest)).isEqualTo("fast-model");
        assertThat(toolNames(lastSubAgentRequest)).isEmpty();
        assertThat(lastSubAgentRequest).contains("Stop investigating");
        assertThat(model(sent.get(ExploreAgent.MAX_STEPS + 1))).isEqualTo("fake-model");

        var items = items(setup);
        assertThat(toolCall(items, "write_file").path("toolStatus").asString()).isEqualTo("denied");
        assertThat(requests.stream().map(request -> request.path("operation").asString()))
                .doesNotContain("write_file");
        var explore = toolCall(items, "explore");
        assertThat(explore.path("result").path("steps").asInt()).isEqualTo(ExploreAgent.MAX_STEPS);
        assertThat(explore.path("resultText").asString())
                .startsWith("RRR")
                .contains("[truncated 500 characters]")
                .hasSizeLessThan(ExploreAgent.MAX_REPORT_CHARS + 100);
    }

    @Test
    void aFailingSubAgentIsReportedToTheMainModelWithoutFailingTheTurn() throws Exception {
        var setup = setUp(false);
        enableExplore(setup);
        llm.enqueueToolCall("call_main", "explore", EXPLORE_CALL);
        llm.enqueueError(400, "{\"error\":{\"message\":\"fast model rejected the request\"}}");
        llm.enqueueText("I will look myself.");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), Map.of())) {
            send(setup, "Explore", null);
            var completed = device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("completed");
        }

        var explore = toolCall(items(setup), "explore");
        assertThat(explore.path("toolStatus").asString()).isEqualTo("failed");
        assertThat(explore.path("status").asString()).isEqualTo("failed");
        assertThat(llm.requests().get(2)).contains("The explore sub-agent failed: fast model rejected the request");
    }

    private UUID enableExplore(Setup setup) {
        var fast = client.post()
                .uri("/api/v1/providers/{id}/models", provider(setup).id())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelKey", "fast-model", "contextWindow", 64000))
                .exchange()
                .expectBody(ProviderDtos.ModelResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
        client.put()
                .uri("/api/v1/me/settings")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("fastModelId", fast.toString()))
                .exchange()
                .expectStatus()
                .isOk();
        updateSession(setup, Map.of("routing", "smart"));
        return fast;
    }

    private ProviderDtos.ProviderResponse provider(Setup setup) {
        return client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(ProviderDtos.ProviderResponse[].class)
                .returnResult()
                .getResponseBody()[0];
    }

    private void updateSession(Setup setup, Map<String, Object> body) {
        client.patch()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private SessionDtos.UsageResponse usage(Setup setup) {
        return client.get()
                .uri("/api/v1/sessions/{id}/usage", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(SessionDtos.UsageResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private List<JsonNode> items(Setup setup) {
        var items = client.get()
                .uri("/api/v1/sessions/{id}/items", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        return StreamSupport.stream(items.spliterator(), false).toList();
    }

    private GatewayTestClient executor(Setup setup, List<JsonNode> requests, Map<String, Object> result)
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
                var response = new HashMap<String, Object>(Map.of("status", "succeeded", "result", result));
                response.put("callId", request.path("callId").asString());
                device.send("exec.result", response);
            }
        });
        device.subscribe(setup.sessionId(), 0);
        return device;
    }

    private static JsonNode toolCall(List<JsonNode> items, String toolName) {
        return items.stream()
                .filter(item -> item.path("toolName").asString().equals(toolName))
                .findFirst()
                .orElseThrow();
    }

    private static String model(String request) {
        return JSON.readTree(request).path("model").asString();
    }

    private static List<String> toolNames(String request) {
        return StreamSupport.stream(JSON.readTree(request).path("tools").spliterator(), false)
                .map(tool -> tool.path("function").path("name").asString())
                .toList();
    }
}
