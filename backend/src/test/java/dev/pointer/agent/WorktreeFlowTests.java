package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

class WorktreeFlowTests extends AgentIntegrationTest {

    @Test
    void worktreeSessionRunsToolsInWorktreeAndMergesIt() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "read_file", "{\"path\":\"README.md\"}");
        llm.enqueueText("Read it.");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var device = executor(
                setup, requests, request -> switch (request.path("operation").asString()) {
                    case "pointer.worktree_create" -> succeeded(Map.of("baseBranch", "main"));
                    case "pointer.worktree_merge" -> succeeded(Map.of("merged", true, "commits", 1));
                    default -> succeeded(Map.of("content", "# Hello", "totalLines", 1));
                })) {
            var created = worktree(setup, "")
                    .expectStatus()
                    .isEqualTo(HttpStatus.CREATED)
                    .expectBody(SessionDtos.SessionResponse.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(created.worktree()).isEqualTo(new SessionDtos.SessionWorktree(branch(setup), "main", "active"));

            send(setup, "Read the readme", null);
            device.awaitEvent("turn.completed", TIMEOUT);

            var merged = worktree(setup, "/merge")
                    .expectStatus()
                    .isOk()
                    .expectBody(SessionDtos.SessionResponse.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(merged.worktree().state()).isEqualTo("merged");
        }

        assertThat(requests)
                .filteredOn(request -> request.path("operation").asString().equals("read_file"))
                .singleElement()
                .satisfies(request ->
                        assertThat(request.path("worktree").asString()).isEqualTo(branch(setup)));
        var merge = requests.stream()
                .filter(request -> request.path("operation").asString().equals("pointer.worktree_merge"))
                .findFirst()
                .orElseThrow();
        assertThat(merge.path("arguments").path("baseBranch").asString()).isEqualTo("main");
        worktree(setup, "/merge").expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void worktreeCannotBeAddedAfterTheFirstTurn() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("Hi.");

        try (var device = executor(setup, new CopyOnWriteArrayList<>(), request -> succeeded(Map.of()))) {
            send(setup, "Hello", null);
            device.awaitEvent("turn.completed", TIMEOUT);

            worktree(setup, "").expectStatus().isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void gitFailureOnDeviceIsReportedAndSessionStaysRegular() throws Exception {
        var setup = setUp(false);

        try (var device = executor(
                setup,
                new CopyOnWriteArrayList<>(),
                request -> Map.of(
                        "status",
                        "failed",
                        "result",
                        Map.of(),
                        "error",
                        Map.of("code", "not_a_git_repository", "message", "Not a repository"),
                        "fileChanges",
                        List.of()))) {
            worktree(setup, "")
                    .expectStatus()
                    .isEqualTo(HttpStatus.CONFLICT)
                    .expectBody(JsonNode.class)
                    .value(body -> assertThat(body.toString()).contains("not_a_git_repository"));
        }

        var session = client.get()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(session.worktree()).isNull();
    }

    private static String branch(Setup setup) {
        return "pointer/" + setup.sessionId().toString().substring(0, 8);
    }

    private RestTestClient.ResponseSpec worktree(Setup setup, String action) {
        return client.post()
                .uri("/api/v1/sessions/{id}/worktree" + action, setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of())
                .exchange();
    }

    private static Map<String, Object> succeeded(Map<String, Object> result) {
        return Map.of("status", "succeeded", "result", result, "fileChanges", List.of());
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
}
