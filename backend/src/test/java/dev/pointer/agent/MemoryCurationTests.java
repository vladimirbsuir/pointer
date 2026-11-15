package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.memory.MemoryCurator;
import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

class MemoryCurationTests extends AgentIntegrationTest {

    private static final String CURATION_REPLY = """
            {"summary": "The user asked to set up deployment. The agent added a deploy script in scripts/deploy.sh \
            and agreed that deployments never happen on Fridays.",
             "memories": [
               {"scope": "project", "kind": "project", "name": "no-friday-deploys",
                "description": "Deployments never happen on Fridays",
                "content": "No deployments on Fridays.\\nWhy: the team is not on call.\\nHow to apply: refuse."},
               {"scope": "user", "kind": "feedback", "name": "use-pnpm", "description": "dup", "content": "dup"},
               {"scope": "project", "kind": "project", "name": "Bad Name", "description": "x", "content": "x"}
             ]}""";

    @Autowired
    private MemoryCurator curator;

    @Test
    void idleSessionsYieldSuggestedMemoriesAndSearchableSummaries() throws Exception {
        var setup = setUp(false);
        createMemory(setup, "use-pnpm", "Use pnpm instead of npm");
        llm.enqueueText("Sure.");

        try (var ws = viewer(setup)) {
            send(setup, "Set up deployment for this project", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
            llm.enqueueText("Added scripts/deploy.sh.");
            send(setup, "Put it into scripts", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
            llm.enqueueText("Understood, never on Fridays.");
            send(setup, "Never deploy on Fridays, the team is not on call", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 3, TIMEOUT);
        }

        assertThat(curator.curateIdleSessions()).isZero();
        archive(setup);
        llm.enqueueText(CURATION_REPLY);
        assertThat(curator.curateIdleSessions()).isEqualTo(1);
        assertThat(curator.curateIdleSessions()).isZero();

        assertThat(llm.requests().getLast())
                .contains("use-pnpm (existing): Use pnpm instead of npm")
                .contains("User: Set up deployment for this project")
                .contains("Agent: Understood, never on Fridays.");
        var suggested = memories(setup, "suggested");
        assertThat(suggested).hasSize(1);
        assertThat(suggested.getFirst().path("name").asString()).isEqualTo("no-friday-deploys");
        assertThat(suggested.getFirst().path("source").asString()).isEqualTo("extracted");
        assertThat(suggested.getFirst().path("sourceSessionId").asString())
                .isEqualTo(setup.sessionId().toString());
        client.post()
                .uri(
                        "/api/v1/memories/{id}/accept",
                        suggested.getFirst().path("id").asString())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(memories(setup, "active")).hasSize(1);

        var second = newSession(setup);
        llm.enqueueToolCall("call_1", "search_sessions", "{\"query\":\"deploy on fridays\"}");
        llm.enqueueText("Last time we added a deploy script.");
        client.post()
                .uri("/api/v1/sessions/{id}/messages", second)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("text", "What did we do about deployment?"))
                .exchange()
                .expectStatus()
                .is2xxSuccessful();
        try (var ws =
                GatewayTestClient.connect(port, setup.auth().accessToken(), setup.deviceId(), List.of("viewer"))) {
            ws.subscribe(UUID.fromString(second), 0);
            ws.awaitEvent("turn.completed", TIMEOUT);
            var call = ws.events().stream()
                    .filter(event -> eventKind(event).equals("item.completed"))
                    .map(event -> event.path("payload").path("event").path("item"))
                    .filter(item -> item.path("type").asString().equals("tool_call"))
                    .findFirst()
                    .orElseThrow();
            var results = call.path("result").path("results");
            assertThat(results).hasSize(1);
            assertThat(results.get(0).path("sessionId").asString())
                    .isEqualTo(setup.sessionId().toString());
            assertThat(results.get(0).path("summary").asString()).contains("scripts/deploy.sh");
        }
    }

    private void createMemory(Setup setup, String name, String description) {
        client.post()
                .uri("/api/v1/memories")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("kind", "feedback", "name", name, "description", description, "content", description))
                .exchange()
                .expectStatus()
                .isCreated();
    }

    private void archive(Setup setup) {
        client.patch()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("archived", true))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private String newSession(Setup setup) {
        var current = client.get()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody();
        return client.post()
                .uri("/api/v1/projects/{id}/sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelId", current.modelId().toString(), "mode", "ask"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody()
                .id()
                .toString();
    }

    private List<JsonNode> memories(Setup setup, String status) {
        var body = client.get()
                .uri("/api/v1/memories?projectId={projectId}&status={status}", setup.projectId(), status)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
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
}
