package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.testing.GatewayTestClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.JsonNode;

class MemoryTests extends AgentIntegrationTest {

    @Test
    void memoriesAreManagedOverRestWithRevisionsAndUndo() {
        var setup = setUp(false);
        var created =
                create(setup, null, "use-pnpm", "Use pnpm instead of npm", "The user wants pnpm in every project.");
        assertThat(created.path("scope").asString()).isEqualTo("user");

        var duplicate = post(setup, "/api/v1/memories", memoryBody(null, "use-pnpm", "Other", "Other"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var invalid = post(setup, "/api/v1/memories", memoryBody(null, "Not Kebab", "Other", "Other"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var id = created.path("id").asString();
        var updated = client.patch()
                .uri("/api/v1/memories/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("content", "Use pnpm, never yarn.", "pinned", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        assertThat(updated.path("pinned").asBoolean()).isTrue();

        var undone = post(setup, "/api/v1/memories/" + id + "/undo", Map.of()).getBody();
        assertThat(undone.path("content").asString()).isEqualTo("The user wants pnpm in every project.");
        assertThat(undone.path("pinned").asBoolean()).isFalse();

        client.delete()
                .uri("/api/v1/memories/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(list(setup, null)).isEmpty();
        post(setup, "/api/v1/memories/" + id + "/undo", Map.of());
        assertThat(list(setup, null)).hasSize(1);

        var projectMemory = create(setup, setup.projectId().toString(), "api-port", "Backend runs on 8080", "Port.");
        assertThat(projectMemory.path("scope").asString()).isEqualTo("project");
        assertThat(list(setup, setup.projectId().toString())).hasSize(1);
        assertThat(list(setup, null)).hasSize(1);
    }

    @Test
    void agentSeesTheIndexAndSavesReadsAndDeletesMemories() throws Exception {
        var setup = setUp(false);
        create(setup, null, "answer-in-russian", "Answer in Russian", "The user prefers answers in Russian.");
        llm.enqueueToolCall(
                "call_1",
                "memory_save",
                "{\"scope\":\"project\",\"kind\":\"project\",\"name\":\"release-freeze\","
                        + "\"description\":\"No merges until Friday\",\"content\":\"Release freeze until Friday.\"}");
        llm.enqueueText("Saved.");

        try (var ws = viewer(setup)) {
            send(setup, "Remember the release freeze", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            assertThat(llm.requests().getFirst())
                    .contains("Memory:")
                    .contains("[user, user] answer-in-russian: Answer in Russian")
                    .contains("\"name\":\"memory_save\"");
            var saved = lastToolCall(ws);
            assertThat(saved.path("toolStatus").asString()).isEqualTo("succeeded");
            assertThat(saved.path("result").path("action").asString()).isEqualTo("created");
            assertThat(saved.path("result").path("scope").asString()).isEqualTo("project");

            llm.enqueueToolCall("call_2", "memory_read", "{\"names\":[\"release-freeze\",\"missing-one\"]}");
            llm.enqueueToolCall("call_3", "memory_delete", "{\"scope\":\"project\",\"name\":\"release-freeze\"}");
            llm.enqueueText("Done.");
            send(setup, "Read it and then forget it", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
        }

        assertThat(llm.requests().get(3)).contains("[project, project] release-freeze: No merges until Friday");
        assertThat(llm.requests().get(4))
                .contains("Release freeze until Friday.")
                .contains("missing-one");
        assertThat(list(setup, setup.projectId().toString())).isEmpty();

        var deleted = jdbcMemory(setup);
        post(setup, "/api/v1/memories/" + deleted + "/undo", Map.of());
        var restored = list(setup, setup.projectId().toString());
        assertThat(restored).hasSize(1);
        assertThat(restored.getFirst().path("readCount").asInt()).isEqualTo(1);
        assertThat(restored.getFirst().path("source").asString()).isEqualTo("agent");
    }

    @Test
    void longIndexesAreCutToTheBudget() throws Exception {
        var setup = setUp(false);
        for (var index = 0; index < 60; index++) {
            create(
                    setup,
                    null,
                    "fact-" + index,
                    "A fairly long description of remembered fact number " + index + " for the budget test",
                    "Content " + index);
        }
        llm.enqueueText("Ok.");

        try (var ws = viewer(setup)) {
            send(setup, "Hello", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
        }

        assertThat(llm.requests().getFirst())
                .contains("more memories are not listed; find them with memory_search")
                .contains("fact-59");
    }

    private String jdbcMemory(Setup setup) {
        return client.get()
                .uri("/api/v1/memories?projectId={projectId}&status=deleted", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .get(0)
                .path("id")
                .asString();
    }

    private JsonNode create(Setup setup, String projectId, String name, String description, String content) {
        var response = post(setup, "/api/v1/memories", memoryBody(projectId, name, description, content));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static Map<String, Object> memoryBody(String projectId, String name, String description, String content) {
        var body = new HashMap<String, Object>(
                Map.of("kind", "user", "name", name, "description", description, "content", content));
        if (projectId != null) {
            body.put("projectId", projectId);
        }
        return body;
    }

    private Response post(Setup setup, String path, Map<String, ?> body) {
        var result = client.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult();
        return new Response(result.getStatus(), result.getResponseBody());
    }

    private record Response(HttpStatusCode getStatusCode, JsonNode getBody) {}

    private List<JsonNode> list(Setup setup, String projectId) {
        var uri = projectId == null ? "/api/v1/memories" : "/api/v1/memories?projectId=" + projectId;
        var body = client.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        var items = new ArrayList<JsonNode>();
        body.forEach(items::add);
        return items;
    }

    private GatewayTestClient viewer(Setup setup) throws Exception {
        var ws = GatewayTestClient.connect(port, setup.auth().accessToken(), setup.deviceId(), List.of("viewer"));
        ws.subscribe(setup.sessionId(), 0);
        ws.await(message -> GatewayTestClient.type(message).equals("session.subscribed"), TIMEOUT);
        return ws;
    }

    private static void awaitTitle(GatewayTestClient ws) {
        ws.await(
                message -> eventKind(message).equals("session.updated")
                        && message.path("payload")
                                .path("event")
                                .path("title")
                                .asString()
                                .equals("Title"),
                TIMEOUT);
    }

    private static long countEvents(GatewayTestClient ws, String kind) {
        return ws.events().stream()
                .filter(event -> eventKind(event).equals(kind))
                .count();
    }

    private static JsonNode lastToolCall(GatewayTestClient ws) {
        return ws.events().stream()
                .filter(event -> eventKind(event).equals("item.completed"))
                .map(event -> event.path("payload").path("event").path("item"))
                .filter(item -> item.path("type").asString().equals("tool_call"))
                .reduce((first, second) -> second)
                .orElseThrow();
    }
}
