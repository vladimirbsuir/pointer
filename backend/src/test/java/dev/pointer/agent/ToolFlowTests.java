package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.testing.GatewayTestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

class ToolFlowTests extends AgentIntegrationTest {

    @Test
    void readToolRunsWithoutApprovalAndResultGoesBackToModel() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "read_file", "{\"path\":\"README.md\"}");
        llm.enqueueText("The readme says hello.");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device =
                executor(setup, requests, request -> succeeded(Map.of("content", "# Hello", "totalLines", 1)))) {
            send(setup, "What is in the readme?", null);
            var completed = device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("completed");

            var toolItem = completedItem(device, "tool_call");
            assertThat(toolItem.path("toolStatus").asString()).isEqualTo("succeeded");
            assertThat(toolItem.path("toolName").asString()).isEqualTo("read_file");
        }

        var execRequests = requests.stream()
                .filter(request -> !request.path("operation").asString().startsWith("pointer."))
                .toList();
        assertThat(execRequests).hasSize(1);
        assertThat(execRequests.getFirst().path("arguments").path("path").asString())
                .isEqualTo("README.md");
        var followUp = llm.requests().get(1);
        assertThat(followUp)
                .contains("\"tool_call_id\":\"call_1\"")
                .contains("# Hello")
                .contains("\"tools\"");
    }

    @Test
    void editRequiresApprovalWithDiffPreviewAndRecordsFileChange() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "edit_file", "{\"path\":\"a.txt\",\"oldText\":\"one\",\"newText\":\"two\"}");
        llm.enqueueText("Done.");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(
                setup,
                requests,
                request -> request.path("dryRun").asBoolean()
                        ? succeeded(Map.of("preview", Map.of("path", "a.txt", "unifiedDiff", "-one\n+two\n")))
                        : Map.of(
                                "status", "succeeded",
                                "result", Map.of("replacements", 1),
                                "fileChanges",
                                        List.of(Map.of(
                                                "path", "a.txt",
                                                "changeKind", "modified",
                                                "before", "one",
                                                "after", "two",
                                                "linesAdded", 1,
                                                "linesRemoved", 1))))) {
            send(setup, "Change one to two", null);

            var requested = device.awaitEvent("approval.requested", TIMEOUT)
                    .path("payload")
                    .path("event");
            assertThat(requested.path("preview").path("kind").asString()).isEqualTo("diff");
            assertThat(requested.path("preview").path("unifiedDiff").asString()).contains("+two");
            assertThat(sessionStatus(setup)).isEqualTo("waiting_for_approval");

            resolve(setup, requested.path("approvalId").asString(), "approved", "once", null);

            device.awaitEvent("turn.completed", TIMEOUT);
            var change =
                    device.awaitEvent("file.changed", TIMEOUT).path("payload").path("event");
            assertThat(change.path("path").asString()).isEqualTo("a.txt");
            assertThat(change.path("linesAdded").asInt()).isEqualTo(1);
        }
        assertThat(requests.stream()
                        .filter(request -> request.path("operation").asString().equals("edit_file")))
                .extracting(request -> request.path("dryRun").asBoolean())
                .containsExactly(true, false);
    }

    @Test
    void changedFileIsListedWithDiffAndCanBeReverted() throws Exception {
        var setup = withAutoApprove(setUp(false));
        llm.enqueueToolCall("call_1", "edit_file", "{\"path\":\"a.txt\",\"oldText\":\"one\",\"newText\":\"two\"}");
        llm.enqueueText("Done.");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(setup, requests, request -> switch (request.path("operation")
                .asString()) {
            case "pointer.restore_file" ->
                request.path("arguments").path("force").asBoolean()
                        ? succeeded(Map.of("restored", true))
                        : Map.of("status", "failed", "error", Map.of("code", "file_modified", "message", "changed"));
            case "edit_file" ->
                Map.of(
                        "status", "succeeded",
                        "result", Map.of("replacements", 1),
                        "fileChanges",
                                List.of(Map.of(
                                        "path", "a.txt",
                                        "changeKind", "modified",
                                        "before", "one",
                                        "after", "two",
                                        "linesAdded", 1,
                                        "linesRemoved", 1)));
            default -> succeeded(Map.of());
        })) {
            send(setup, "Change one to two", null);
            device.awaitEvent("turn.completed", TIMEOUT);

            var files = getJson(setup, "/api/v1/sessions/{id}/changes");
            assertThat(files.get(0).path("path").asString()).isEqualTo("a.txt");
            assertThat(files.get(0).path("changeKind").asString()).isEqualTo("modified");
            assertThat(files.get(0).path("reverted").asBoolean()).isFalse();

            var diff = getJson(setup, "/api/v1/sessions/{id}/changes/file?path=a.txt");
            assertThat(diff.path("before").asString()).isEqualTo("one");
            assertThat(diff.path("after").asString()).isEqualTo("two");

            revert(setup, false).expectStatus().isEqualTo(HttpStatus.CONFLICT);
            var reverted = revert(setup, true)
                    .expectStatus()
                    .isOk()
                    .expectBody(JsonNode.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(reverted.path("reverted").asBoolean()).isTrue();
            device.awaitEvent("file.reverted", TIMEOUT);
            revert(setup, true).expectStatus().isEqualTo(HttpStatus.CONFLICT);
        }
        var restore = requests.stream()
                .filter(request -> request.path("operation").asString().equals("pointer.restore_file"))
                .toList();
        assertThat(restore).hasSize(2);
        assertThat(restore.getLast().path("arguments").path("content").asString())
                .isEqualTo("one");
        assertThat(restore.getLast().path("arguments").path("expectedContent").asString())
                .isEqualTo("two");
    }

    @Test
    void deniedToolCallIsReportedToModelWithUserMessage() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "run_command", "{\"command\":\"npm publish\"}");
        llm.enqueueText("Understood.");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(setup, requests, request -> succeeded(Map.of("exitCode", 0)))) {
            send(setup, "Publish it", null);
            var requested = device.awaitEvent("approval.requested", TIMEOUT)
                    .path("payload")
                    .path("event");
            assertThat(requested.path("preview").path("command").asString()).isEqualTo("npm publish");

            resolve(setup, requested.path("approvalId").asString(), "denied", "once", "not yet");
            device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completedItem(device, "tool_call").path("toolStatus").asString())
                    .isEqualTo("denied");
        }
        assertThat(requests.stream().map(request -> request.path("operation").asString()))
                .doesNotContain("run_command");
        assertThat(llm.requests().get(1)).contains("denied").contains("not yet");
    }

    @Test
    void stoppingWhileAwaitingApprovalCancelsApprovalAndToolCall() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "run_command", "{\"command\":\"npm install\"}");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), request -> succeeded(Map.of()))) {
            send(setup, "Install", null);
            device.awaitEvent("approval.requested", TIMEOUT);

            client.post()
                    .uri("/api/v1/sessions/{id}/cancel", setup.sessionId())
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .isAccepted();

            var completed = device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("cancelled");
            var resolved = device.awaitEvent("approval.resolved", TIMEOUT)
                    .path("payload")
                    .path("event");
            assertThat(resolved.path("decision").asString()).isEqualTo("cancelled");
            var toolItem = completedItem(device, "tool_call");
            assertThat(toolItem.path("status").asString()).isEqualTo("cancelled");
            assertThat(toolItem.path("toolStatus").asString()).isEqualTo("cancelled");
        }
        assertThat(sessionStatus(setup)).isEqualTo("idle");
    }

    @Test
    void sessionGrantAllowsSameCommandPrefixAgain() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "run_command", "{\"command\":\"npm test\"}");
        llm.enqueueToolCall("call_2", "run_command", "{\"command\":\"npm test -- --watch=false\"}");
        llm.enqueueText("Tests pass.");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(setup, requests, request -> succeeded(Map.of("exitCode", 0, "output", "ok")))) {
            send(setup, "Run the tests", null);
            var requested = device.awaitEvent("approval.requested", TIMEOUT)
                    .path("payload")
                    .path("event");
            resolve(setup, requested.path("approvalId").asString(), "approved", "session", null);

            device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(device.events().stream().filter(event -> eventKind(event).equals("approval.requested")))
                    .hasSize(1);
        }
        assertThat(requests.stream()
                        .filter(request -> request.path("operation").asString().equals("run_command")))
                .hasSize(2);
    }

    @Test
    void commandRuleDenyBlocksWithoutAsking() throws Exception {
        var setup = setUp(false);
        client.put()
                .uri("/api/v1/permissions")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("commandRules", List.of(Map.of("prefix", "rm", "policy", "deny"))))
                .exchange()
                .expectStatus()
                .isOk();
        llm.enqueueToolCall("call_1", "run_command", "{\"command\":\"rm  -rf build\"}");
        llm.enqueueText("Blocked.");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), request -> succeeded(Map.of()))) {
            send(setup, "Clean up", null);
            device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(device.events().stream().filter(event -> eventKind(event).equals("approval.requested")))
                    .isEmpty();
            assertThat(completedItem(device, "tool_call").path("toolStatus").asString())
                    .isEqualTo("denied");
        }
    }

    @Test
    void writeToolIsUnavailableInAskMode() throws Exception {
        var setup = setUp(false, "ask");
        llm.enqueueToolCall("call_1", "write_file", "{\"path\":\"x.txt\",\"content\":\"x\"}");
        llm.enqueueText("Sorry.");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), request -> succeeded(Map.of()))) {
            send(setup, "Write x", null);
            device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completedItem(device, "tool_call").path("toolStatus").asString())
                    .isEqualTo("denied");
        }
        assertThat(llm.requests().getFirst()).contains("read_file").doesNotContain("\"write_file\"");
    }

    @Test
    void toolCallWaitsForExecutorDeviceToConnect() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "list_directory", "{\"path\":\".\"}");
        llm.enqueueText("Listed.");

        var viewerDevice = UUID.randomUUID();
        try (var viewer =
                GatewayTestClient.connect(port, tokenForDevice(setup, viewerDevice), viewerDevice, List.of("viewer"))) {
            viewer.subscribe(setup.sessionId(), 0);
            send(setup, "List files", null);
            viewer.await(
                    message -> eventKind(message).equals("status.changed")
                            && message.path("payload")
                                    .path("event")
                                    .path("status")
                                    .asString()
                                    .equals("waiting_for_executor"),
                    TIMEOUT);

            try (var device =
                    executor(setup, new CopyOnWriteArrayList<>(), request -> succeeded(Map.of("entries", List.of())))) {
                viewer.awaitEvent("turn.completed", TIMEOUT);
            }
        }
        assertThat(sessionStatus(setup)).isEqualTo("idle");
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

    private RestTestClient.ResponseSpec revert(Setup setup, boolean force) {
        return client.post()
                .uri("/api/v1/sessions/{id}/changes/revert", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("path", "a.txt", "force", force))
                .exchange();
    }

    private String tokenForDevice(Setup setup, UUID deviceId) {
        return login(setup.email(), PASSWORD, deviceId).accessToken();
    }

    private GatewayTestClient executor(
            Setup setup, List<JsonNode> requests, Function<JsonNode, Map<String, Object>> responder) throws Exception {
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
                var response = new HashMap<String, Object>(responder.apply(request));
                response.put("callId", request.path("callId").asString());
                device.send("exec.result", response);
            }
        });
        device.subscribe(setup.sessionId(), 0);
        return device;
    }

    private void resolve(Setup setup, String approvalId, String decision, String scope, String message) {
        var body = new HashMap<String, Object>(Map.of("decision", decision, "scope", scope));
        if (message != null) {
            body.put("message", message);
        }
        client.post()
                .uri("/api/v1/sessions/{id}/approvals/{approvalId}", setup.sessionId(), approvalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    private static JsonNode completedItem(GatewayTestClient device, String itemType) {
        return device.events().stream()
                .filter(event -> eventKind(event).equals("item.completed"))
                .map(event -> event.path("payload").path("event").path("item"))
                .filter(item -> item.path("type").asString().equals(itemType))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> succeeded(Map<String, Object> result) {
        return Map.of("status", "succeeded", "result", result);
    }
}
