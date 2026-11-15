package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RoutingTests extends AgentIntegrationTest {

    private static final String OVERLOADED = "{\"error\":{\"message\":\"provider overloaded\"}}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        assertThat(requests.get(requests.size() - 3)).contains("\"model\":\"fast-model\"");
        assertThat(requests.get(requests.size() - 2)).contains("\"model\":\"fast-model\"");
        assertThat(requests.getLast()).contains("\"model\":\"fake-model\"");
        assertThat(usage(setup).entries())
                .extracting(entry -> entry.modelLabel() + "/" + entry.role() + "/" + entry.purpose())
                .contains("fake-model/main/compaction");
    }

    @Test
    void smartRoutingRetriesTheSameModelOnceBeforeAnyFallback() throws Exception {
        var setup = setUp(false);
        updateSession(setup, Map.of("routing", "smart"));
        llm.enqueueError(503, OVERLOADED);
        llm.enqueueText("Recovered answer.");

        try (var ws = viewer(setup)) {
            send(setup, "Hello", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
        }

        assertThat(llm.requests().subList(0, 2)).allMatch(request -> request.contains("\"model\":\"fake-model\""));
        assertThat(itemTypes(setup)).doesNotContain("model_switched");
        assertThat(usage(setup).entries()).allMatch(entry -> entry.role().equals("main"));
    }

    @Test
    void smartRoutingWalksTheFallbackListAndStaysOnTheWorkingModelForTheTurn() throws Exception {
        var setup = setUp(false);
        var provider = provider(setup);
        var main = provider.models().getFirst().id();
        var first = addModel(setup, provider.id(), "backup-a");
        var second = addModel(setup, provider.id(), "backup-b");
        replaceFallbacks(setup, main, List.of(first, second)).expectStatus().isOk();
        updateSession(setup, Map.of("routing", "smart"));
        llm.enqueueError(503, OVERLOADED);
        llm.enqueueError(503, OVERLOADED);
        llm.enqueueError(429, OVERLOADED);
        llm.enqueueToolCall("call_1", "no_such_tool", "{}");
        llm.enqueueText("Done on the backup.");

        try (var ws = viewer(setup)) {
            send(setup, "Hello", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
        }

        assertThat(llm.requests().subList(0, 5))
                .extracting(RoutingTests::requestedModel)
                .containsExactly("fake-model", "fake-model", "backup-a", "backup-b", "backup-b");
        var switches = items(setup).stream()
                .filter(item -> item.path("type").asString().equals("model_switched"))
                .toList();
        assertThat(switches)
                .extracting(item ->
                        item.path("from").asString() + ">" + item.path("to").asString())
                .containsExactly("fake-model>backup-a", "backup-a>backup-b");
        assertThat(switches.getFirst().path("reason").asString()).isEqualTo("provider overloaded");
        assertThat(switches.getFirst().path("status").asString()).isEqualTo("completed");
        assertThat(usage(setup).entries())
                .extracting(entry -> entry.modelLabel() + "/" + entry.role() + "/" + entry.purpose())
                .contains("backup-b/fallback/step");
        assertThat(session(setup).modelId()).isEqualTo(main);
    }

    @Test
    void routingOffFailsTheTurnWithoutRetryOrFallback() throws Exception {
        var setup = setUp(false);
        var provider = provider(setup);
        var backup = addModel(setup, provider.id(), "backup-a");
        replaceFallbacks(setup, provider.models().getFirst().id(), List.of(backup))
                .expectStatus()
                .isOk();
        llm.enqueueError(503, OVERLOADED);

        try (var ws = viewer(setup)) {
            send(setup, "Hello", null);
            var completed = ws.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("failed");
        }

        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void nonRetryableErrorsFailTheTurnEvenWithFallbacks() throws Exception {
        var setup = setUp(false);
        var provider = provider(setup);
        var backup = addModel(setup, provider.id(), "backup-a");
        replaceFallbacks(setup, provider.models().getFirst().id(), List.of(backup))
                .expectStatus()
                .isOk();
        updateSession(setup, Map.of("routing", "smart"));
        llm.enqueueError(400, "{\"error\":{\"message\":\"context too long\"}}");

        try (var ws = viewer(setup)) {
            send(setup, "Hello", null);
            var completed = ws.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("failed");
        }

        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void fallbackListsAreValidatedAndReturnedWithTheModel() {
        var setup = setUp(false);
        var provider = provider(setup);
        var main = provider.models().getFirst().id();
        var backup = addModel(setup, provider.id(), "backup-a");
        var foreign = provider(setUp(false)).models().getFirst().id();

        replaceFallbacks(setup, main, List.of(main)).expectStatus().isBadRequest();
        replaceFallbacks(setup, main, List.of(backup, backup)).expectStatus().isBadRequest();
        replaceFallbacks(setup, main, List.of(foreign)).expectStatus().isNotFound();
        replaceFallbacks(setup, foreign, List.of(backup)).expectStatus().isNotFound();
        replaceFallbacks(setup, main, List.of(backup)).expectStatus().isOk();

        assertThat(provider(setup).models().stream()
                        .filter(model -> model.id().equals(main))
                        .findFirst()
                        .orElseThrow()
                        .fallbackModelIds())
                .containsExactly(backup);
        assertThat(client.get()
                        .uri("/api/v1/models/{id}/fallbacks", main)
                        .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                        .exchange()
                        .expectBody(ProviderDtos.FallbacksResponse.class)
                        .returnResult()
                        .getResponseBody()
                        .modelIds())
                .containsExactly(backup);
        replaceFallbacks(setup, main, List.of()).expectStatus().isOk();
        assertThat(provider(setup).models().stream()
                        .filter(model -> model.id().equals(main))
                        .findFirst()
                        .orElseThrow()
                        .fallbackModelIds())
                .isEmpty();
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

    private RestTestClient.ResponseSpec replaceFallbacks(Setup setup, UUID modelId, List<UUID> fallbackIds) {
        return client.put()
                .uri("/api/v1/models/{id}/fallbacks", modelId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelIds", fallbackIds))
                .exchange();
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

    private List<String> itemTypes(Setup setup) {
        return items(setup).stream().map(item -> item.path("type").asString()).toList();
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

    private static String requestedModel(String request) {
        return JSON.readTree(request).path("model").asString();
    }

    private RoleModels addRoleModels(Setup setup) {
        var provider = provider(setup);
        return new RoleModels(
                addModel(setup, provider.id(), "fast-model").toString(),
                addModel(setup, provider.id(), "plan-model").toString());
    }

    private UUID addModel(Setup setup, UUID providerId, String key) {
        return client.post()
                .uri("/api/v1/providers/{id}/models", providerId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelKey", key, "contextWindow", 8000))
                .exchange()
                .expectBody(ProviderDtos.ModelResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
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
