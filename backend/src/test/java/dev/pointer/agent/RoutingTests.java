package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RoutingTests extends AgentIntegrationTest {

    @Test
    void smartRoutingSendsPlanTurnsToThePlanModelAndTitlesToTheFastModel() throws Exception {
        var setup = setUp(false, "plan");
        var models = addRoleModels(setup);
        updateSettings(
                setup, Map.of("fastModelId", models.fast(), "planModelId", models.plan(), "routingDefault", "smart"));
        updateSession(setup, Map.of("routing", "smart"));
        llm.enqueueText("Here is how I would plan it.");
        llm.enqueueText("Title");

        try (var ws = viewer(setup)) {
            send(setup, "Plan a greeting feature", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
        }

        assertThat(llm.requests().get(0)).contains("\"model\":\"plan-model\"").contains("propose_plan");
        assertThat(llm.requests().get(1)).contains("\"model\":\"fast-model\"");
        assertThat(usage(setup).entries())
                .extracting(entry -> entry.modelLabel() + "/" + entry.role() + "/" + entry.purpose())
                .containsExactlyInAnyOrder("plan-model/plan/step", "fast-model/fast/title");
        assertThat(newSession(setup).routing()).isEqualTo("smart");
    }

    @Test
    void routingOffKeepsEveryCallOnTheSessionModel() throws Exception {
        var setup = setUp(false, "plan");
        var models = addRoleModels(setup);
        updateSettings(setup, Map.of("fastModelId", models.fast(), "planModelId", models.plan()));
        llm.enqueueText("Plan.");
        llm.enqueueText("Title");

        try (var ws = viewer(setup)) {
            send(setup, "Plan something", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
        }

        assertThat(llm.requests()).allMatch(request -> request.contains("\"model\":\"fake-model\""));
        assertThat(usage(setup).entries()).allMatch(entry -> entry.role().equals("main"));
    }

    @Test
    void compactionUsesTheFastModelAndFallsBackToTheStepModel() throws Exception {
        var setup = setUp(false);
        var models = addRoleModels(setup);
        updateSettings(setup, Map.of("fastModelId", models.fast()));
        updateSession(setup, Map.of("routing", "smart"));
        llm.enqueueText("First answer.");
        llm.enqueueText("Title");

        try (var ws = viewer(setup)) {
            send(setup, "Remember the number 42", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            llm.enqueueError(500, "{\"error\":{\"message\":\"fast model down\"}}");
            llm.enqueueText("SUMMARY: the number is 42.");
            client.post()
                    .uri("/api/v1/sessions/{id}/compact", setup.sessionId())
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .is2xxSuccessful();
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
        }

        var requests = llm.requests();
        assertThat(requests.get(requests.size() - 2)).contains("\"model\":\"fast-model\"");
        assertThat(requests.getLast()).contains("\"model\":\"fake-model\"");
        assertThat(usage(setup).entries())
                .extracting(entry -> entry.modelLabel() + "/" + entry.role() + "/" + entry.purpose())
                .contains("fake-model/main/compaction");
    }

    private RoleModels addRoleModels(Setup setup) {
        var provider = client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(ProviderDtos.ProviderResponse[].class)
                .returnResult()
                .getResponseBody()[0];
        return new RoleModels(
                addModel(setup, provider.id(), "fast-model"), addModel(setup, provider.id(), "plan-model"));
    }

    private String addModel(Setup setup, UUID providerId, String key) {
        return client.post()
                .uri("/api/v1/providers/{id}/models", providerId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelKey", key, "contextWindow", 8000))
                .exchange()
                .expectBody(ProviderDtos.ModelResponse.class)
                .returnResult()
                .getResponseBody()
                .id()
                .toString();
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

    private SessionDtos.SessionResponse newSession(Setup setup) {
        return client.post()
                .uri("/api/v1/projects/{id}/sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("mode", "ask"))
                .exchange()
                .expectStatus()
                .isCreated()
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

    private record RoleModels(String fast, String plan) {}
}
