package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.testing.GatewayTestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CustomToolTests extends AgentIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> INTERPRETERS = List.of("python", "powershell");

    @Test
    void approvedProposalIsStoredOfferedAndExecutedOnTheDevice() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "propose_tool", proposal("count_lines", "print(1)", "project"));
        llm.enqueueToolCall("call_2", "count_lines", "{\"path\":\"a.txt\"}");
        llm.enqueueText("There is one line.");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(setup, requests, INTERPRETERS)) {
            send(setup, "Count lines", null);
            var requested = device.awaitEvent("approval.requested", TIMEOUT)
                    .path("payload")
                    .path("event");
            var preview = requested.path("preview");
            assertThat(preview.path("kind").asString()).isEqualTo("tool_proposal");
            assertThat(preview.path("name").asString()).isEqualTo("count_lines");
            assertThat(preview.path("body").asString()).isEqualTo("print(1)");
            assertThat(preview.path("version").asInt()).isEqualTo(1);
            assertThat(preview.path("scopeFixed").asBoolean()).isFalse();
            assertThat(preview.path("defaultPolicy").asString()).isEqualTo("ask");

            resolve(
                    setup,
                    requested.path("approvalId").asString(),
                    "approved",
                    Map.of("scope", "user", "defaultPolicy", "allow", "modes", List.of("agent", "ask")));
            device.awaitEvent("turn.completed", TIMEOUT);

            var toolCalls = completedItems(device, "tool_call");
            assertThat(toolCalls.getFirst().path("toolStatus").asString()).isEqualTo("succeeded");
            assertThat(toolCalls.getFirst().path("result").path("scope").asString())
                    .isEqualTo("user");
            assertThat(toolCalls.get(1).path("toolStatus").asString()).isEqualTo("succeeded");
            assertThat(toolCalls.get(1).path("customToolVersion").asInt()).isEqualTo(1);
        }

        assertThat(llm.requests().getFirst())
                .contains("\"name\":\"propose_tool\"")
                .contains("python, powershell")
                .doesNotContain("\"name\":\"count_lines\"");
        assertThat(llm.requests().get(1)).contains("\"name\":\"count_lines\"");
        var run = requests.stream()
                .filter(request -> request.path("operation").asString().equals("custom_tool.run"))
                .findFirst()
                .orElseThrow();
        assertThat(run.path("arguments").path("interpreter").asString()).isEqualTo("python");
        assertThat(run.path("arguments").path("body").asString()).isEqualTo("print(1)");
        assertThat(run.path("arguments").path("arguments").path("path").asString())
                .isEqualTo("a.txt");

        var tools = getJson(setup, "/api/v1/custom-tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("scope").asString()).isEqualTo("user");
        assertThat(tools.get(0).path("defaultPolicy").asString()).isEqualTo("allow");
        assertThat(tools.get(0).path("modes").toString()).isEqualTo("[\"ask\",\"agent\"]");
    }

    @Test
    void proposalIsOfferedOnlyWhenTheDeviceReportsInterpreters() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("Hi.");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), List.of())) {
            send(setup, "Hello", null);
            device.awaitEvent("turn.completed", TIMEOUT);
        }
        assertThat(llm.requests().getFirst()).doesNotContain("propose_tool");
    }

    @Test
    void secondProposalWithTheSameNameIsANewVersionThatCanBeRolledBack() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "propose_tool", proposal("greet", "print('hi')", "project"));
        llm.enqueueText("Done.");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), INTERPRETERS)) {
            send(setup, "Make a greeting tool", null);
            var first = device.awaitEvent("approval.requested", TIMEOUT)
                    .path("payload")
                    .path("event");
            resolve(setup, first.path("approvalId").asString(), "approved", null);
            device.awaitEvent("turn.completed", TIMEOUT);
            device.await(
                    message -> eventKind(message).equals("session.updated")
                            && message.path("payload")
                                    .path("event")
                                    .path("title")
                                    .asString()
                                    .equals("Title"),
                    TIMEOUT);

            llm.enqueueToolCall("call_2", "propose_tool", proposal("greet", "print('hello')", "user"));
            llm.enqueueText("Updated.");
            send(setup, "Make it say hello", null);
            var second = awaitSecondApproval(device);
            var preview = second.path("preview");
            assertThat(preview.path("version").asInt()).isEqualTo(2);
            assertThat(preview.path("scope").asString()).isEqualTo("project");
            assertThat(preview.path("scopeFixed").asBoolean()).isTrue();
            assertThat(preview.path("previous").path("body").asString()).isEqualTo("print('hi')");
            resolve(setup, second.path("approvalId").asString(), "approved", null);
            awaitTurns(device, 2);
        }

        var tool = getJson(setup, "/api/v1/custom-tools").get(0);
        assertThat(tool.path("version").asInt()).isEqualTo(2);
        assertThat(tool.path("scope").asString()).isEqualTo("project");
        var toolId = tool.path("id").asString();
        var versions = getJson(setup, "/api/v1/custom-tools/" + toolId + "/versions");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).path("current").asBoolean()).isTrue();

        var restored = client.post()
                .uri("/api/v1/custom-tools/{id}/versions/1/restore", toolId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        assertThat(restored.path("version").asInt()).isEqualTo(1);
    }

    @Test
    void proposalWithABuiltinNameFailsWithoutAskingTheUser() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "propose_tool", proposal("read_file", "print(1)", "project"));
        llm.enqueueText("Sorry.");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), INTERPRETERS)) {
            send(setup, "Make a tool", null);
            device.awaitEvent("turn.completed", TIMEOUT);
            var item = completedItems(device, "tool_call").getFirst();
            assertThat(item.path("toolStatus").asString()).isEqualTo("failed");
            assertThat(item.path("error").path("code").asString()).isEqualTo("invalid_proposal");
            assertThat(device.events().stream().map(GatewayTestClient::eventKind))
                    .doesNotContain("approval.requested");
        }
        assertThat(getJson(setup, "/api/v1/custom-tools")).isEmpty();
    }

    @Test
    void rejectedProposalIsReportedToTheModelAndNotStored() throws Exception {
        var setup = withAutoApprove(setUp(false));
        llm.enqueueToolCall("call_1", "propose_tool", proposal("cleanup", "print(1)", "project"));
        llm.enqueueText("Understood.");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), INTERPRETERS)) {
            send(setup, "Make a cleanup tool", null);
            var requested = device.awaitEvent("approval.requested", TIMEOUT)
                    .path("payload")
                    .path("event");
            resolveDenied(setup, requested.path("approvalId").asString(), "too risky");
            device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completedItems(device, "tool_call")
                            .getFirst()
                            .path("toolStatus")
                            .asString())
                    .isEqualTo("denied");
        }
        assertThat(llm.requests().get(1)).contains("too risky");
        assertThat(getJson(setup, "/api/v1/custom-tools")).isEmpty();
    }

    private static String proposal(String name, String body, String scope) {
        return JSON.writeValueAsString(Map.of(
                "name",
                name,
                "description",
                "Test tool " + name,
                "parameters",
                Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"))),
                "interpreter",
                "python",
                "body",
                body,
                "scope",
                scope));
    }

    private JsonNode awaitSecondApproval(GatewayTestClient device) throws Exception {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            var approvals = device.events().stream()
                    .filter(event -> eventKind(event).equals("approval.requested"))
                    .toList();
            if (approvals.size() >= 2) {
                return approvals.get(1).path("payload").path("event");
            }
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private void awaitTurns(GatewayTestClient device, int count) throws Exception {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (device.events().stream()
                        .filter(event -> eventKind(event).equals("turn.completed"))
                        .count()
                < count) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private Setup withAutoApprove(Setup setup) {
        client.patch()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("autoApprove", true))
                .exchange()
                .expectStatus()
                .isOk();
        return setup;
    }

    private JsonNode getJson(Setup setup, String uri) {
        return client.get()
                .uri(uri, setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private GatewayTestClient executor(Setup setup, List<JsonNode> requests, List<String> interpreters)
            throws Exception {
        var device = GatewayTestClient.connect(
                port,
                setup.auth().accessToken(),
                setup.deviceId(),
                List.of("viewer", "executor"),
                List.of(setup.projectId().toString()),
                interpreters);
        device.onMessage(message -> {
            if (type(message).equals("exec.request")) {
                var request = message.path("payload");
                requests.add(request);
                var response = new HashMap<String, Object>(
                        Map.of("status", "succeeded", "result", Map.of("output", "1\n"), "fileChanges", List.of()));
                response.put("callId", request.path("callId").asString());
                device.send("exec.result", response);
            }
        });
        device.subscribe(setup.sessionId(), 0);
        return device;
    }

    private void resolve(Setup setup, String approvalId, String decision, Map<String, Object> tool) {
        var body = new HashMap<String, Object>(Map.of("decision", decision, "scope", "once"));
        if (tool != null) {
            body.put("tool", tool);
        }
        client.post()
                .uri("/api/v1/sessions/{id}/approvals/{approvalId}", setup.sessionId(), approvalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    private void resolveDenied(Setup setup, String approvalId, String message) {
        client.post()
                .uri("/api/v1/sessions/{id}/approvals/{approvalId}", setup.sessionId(), approvalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("decision", "denied", "scope", "once", "message", message))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    private static List<JsonNode> completedItems(GatewayTestClient device, String itemType) {
        return device.events().stream()
                .filter(event -> eventKind(event).equals("item.completed"))
                .map(event -> event.path("payload").path("event").path("item"))
                .filter(item -> item.path("type").asString().equals(itemType))
                .toList();
    }
}
