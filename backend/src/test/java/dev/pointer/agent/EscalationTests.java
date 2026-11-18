package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EscalationTests extends AgentIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void newSessionsTakeTheDefaultAndSessionsCanSwitchEscalation() {
        var setup = setUp(false);
        assertThat(session(setup).escalation()).isEqualTo("off");

        updateSettings(setup, Map.of("escalationDefault", "on"));
        var created = client.post()
                .uri("/api/v1/projects/{id}/sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("mode", "agent"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created.escalation()).isEqualTo("on");

        updateSession(setup, Map.of("escalation", "on"));
        assertThat(session(setup).escalation()).isEqualTo("on");
        client.patch()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("escalation", "sometimes"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void aStrugglingTurnMovesToTheSessionModelAndTheNextTurnStartsCheapAgain() throws Exception {
        var setup = escalatingSession("agent");
        llm.enqueueToolCall("call_1", "no_such_tool", "{}");
        llm.enqueueToolCall("call_2", "no_such_tool", "{}");
        llm.enqueueText("Gave up on the tool.");
        llm.enqueueText("Title");

        try (var ws = viewer(setup)) {
            send(setup, "Use the tool", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            var notice = lastItem(ws, "model_escalated");
            assertThat(notice.path("from").asString()).isEqualTo("fast-model");
            assertThat(notice.path("to").asString()).isEqualTo("fake-model");
            assertThat(notice.path("reason").asString()).isEqualTo("struggle");
            assertThat(notice.path("score").decimalValue()).isEqualByComparingTo("2.5");
            assertThat(StreamSupport.stream(notice.path("signals").spliterator(), false)
                            .map(JsonNode::asString))
                    .containsExactly("repeat_call", "malformed_call");

            llm.enqueueText("Second answer.");
            send(setup, "Next question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
        }

        var requests = llm.requests();
        assertThat(requestedModels(requests.subList(0, 3))).containsExactly("fast-model", "fast-model", "fake-model");
        assertThat(requestedModel(requests.getLast())).isEqualTo("fast-model");
        var rows = signals(setup, firstTurnId(setup));
        assertThat(rows).extracting(row -> row.path("escalated").asBoolean()).containsExactly(false, true, false);
        assertThat(rows)
                .extracting(row -> row.path("modelLabel").asString())
                .containsExactly("fast-model", "fast-model", "fake-model");
        assertThat(usage(setup).entries())
                .extracting(entry -> entry.modelLabel() + "/" + entry.role() + "/" + entry.purpose())
                .contains("fast-model/fast/step", "fake-model/main/step");
        var firstTurn = usage(setup).turns().getFirst();
        assertThat(firstTurn.number()).isEqualTo(1);
        assertThat(firstTurn.escalated()).isTrue();
        assertThat(firstTurn.models())
                .extracting(steps -> steps.modelLabel() + "/" + steps.steps())
                .containsExactly("fast-model/2", "fake-model/1");
    }

    @Test
    void escalationOffPlanModeAndALargeContextKeepTheSessionModel() throws Exception {
        var off = escalatingSession("agent");
        updateSession(off, Map.of("escalation", "off"));
        assertThat(firstStepModel(off)).isEqualTo("fake-model");

        var plan = escalatingSession("plan");
        assertThat(firstStepModel(plan)).isEqualTo("fake-model");

        var large = escalatingSession("agent");
        llm.enqueueStream(
                Duration.ZERO,
                "{\"choices\":[{\"delta\":{\"content\":\"Long answer\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":7000,\"completion_tokens\":10}}",
                "[DONE]");
        llm.enqueueText("Title");
        try (var ws = viewer(large)) {
            send(large, "First question", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
            llm.enqueueText("Second answer.");
            send(large, "Second question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
        }
        var requests = llm.requests();
        assertThat(requestedModel(requests.get(requests.size() - 3))).isEqualTo("fast-model");
        assertThat(requestedModel(requests.getLast())).isEqualTo("fake-model");
    }

    private String firstStepModel(Setup setup) throws Exception {
        llm.enqueueText("Answer.");
        llm.enqueueText("Title");
        try (var ws = viewer(setup)) {
            send(setup, "Question", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
        }
        var requests = llm.requests();
        return requestedModel(requests.get(requests.size() - 2));
    }

    Setup escalatingSession(String mode) {
        var setup = setUp(false, mode);
        var provider = provider(setup);
        var fast = client.post()
                .uri("/api/v1/providers/{id}/models", provider.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelKey", "fast-model", "contextWindow", 8000))
                .exchange()
                .expectBody(ProviderDtos.ModelResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
        client.patch()
                .uri("/api/v1/providers/{id}/models/{modelId}", provider.id(), mainModel(provider))
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("contextWindow", 100_000))
                .exchange()
                .expectStatus()
                .isOk();
        updateSettings(setup, Map.of("fastModelId", fast.toString()));
        updateSession(setup, Map.of("routing", "smart", "escalation", "on"));
        return setup;
    }

    private static UUID mainModel(ProviderDtos.ProviderResponse provider) {
        return provider.models().stream()
                .filter(model -> model.modelKey().equals("fake-model"))
                .findFirst()
                .orElseThrow()
                .id();
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

    UUID firstTurnId(Setup setup) {
        var items = client.get()
                .uri("/api/v1/sessions/{id}/items", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        return UUID.fromString(items.get(0).path("turnId").asString());
    }

    List<JsonNode> signals(Setup setup, UUID turnId) {
        var rows = client.get()
                .uri("/api/v1/sessions/{id}/turns/{turnId}/signals", setup.sessionId(), turnId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        return StreamSupport.stream(rows.spliterator(), false).toList();
    }

    private void updateSettings(Setup setup, Map<String, Object> body) {
        client.put()
                .uri("/api/v1/me/settings")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange()
                .expectStatus()
                .isOk();
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

    private SessionDtos.SessionResponse session(Setup setup) {
        return client.get()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody();
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

    static List<String> requestedModels(List<String> requests) {
        return requests.stream().map(EscalationTests::requestedModel).toList();
    }

    static String requestedModel(String request) {
        return JSON.readTree(request).path("model").asString();
    }

    GatewayTestClient viewer(Setup setup) throws Exception {
        var ws = GatewayTestClient.connect(port, setup.auth().accessToken(), setup.deviceId(), List.of("viewer"));
        ws.subscribe(setup.sessionId(), 0);
        ws.await(message -> GatewayTestClient.type(message).equals("session.subscribed"), TIMEOUT);
        return ws;
    }

    static void awaitTitle(GatewayTestClient ws) {
        ws.await(
                message -> eventKind(message).equals("session.updated")
                        && message.path("payload")
                                .path("event")
                                .path("title")
                                .asString()
                                .equals("Title"),
                TIMEOUT);
    }

    static long countEvents(GatewayTestClient ws, String kind) {
        return ws.events().stream()
                .filter(event -> eventKind(event).equals(kind))
                .count();
    }

    static JsonNode lastItem(GatewayTestClient ws, String type) {
        return ws.events().stream()
                .filter(event -> List.of("item.created", "item.updated", "item.completed")
                        .contains(eventKind(event)))
                .map(event -> event.path("payload").path("event").path("item"))
                .filter(item -> item.path("type").asString().equals(type))
                .reduce((first, second) -> second)
                .orElseThrow();
    }
}
