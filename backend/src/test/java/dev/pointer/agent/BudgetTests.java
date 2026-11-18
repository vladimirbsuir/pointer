package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

class BudgetTests extends AgentIntegrationTest {

    @Test
    void usageIsRecordedPerModelAndPurposeAndTheBudgetStopsTurns() throws Exception {
        var setup = setUp(false);
        priceModel(setup);
        llm.enqueueText("First answer.");
        llm.enqueueText("Title");

        try (var ws = viewer(setup)) {
            send(setup, "Hello", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            var usage = usage(setup);
            assertThat(usage.estimatedCost()).isEqualByComparingTo("0.017");
            assertThat(usage.entries()).hasSize(2);
            assertThat(usage.entries())
                    .extracting(SessionDtos.UsageEntry::purpose)
                    .containsExactlyInAnyOrder("step", "title");
            var step = usage.entries().stream()
                    .filter(entry -> entry.purpose().equals("step"))
                    .findFirst()
                    .orElseThrow();
            assertThat(step.modelLabel()).isEqualTo("fake-model");
            assertThat(step.role()).isEqualTo("main");
            assertThat(step.calls()).isEqualTo(1);
            assertThat(step.promptTokens()).isEqualTo(12);
            assertThat(step.costUsd()).isEqualByComparingTo("0.017");

            var updated = updateBudget(setup, new BigDecimal("0.01"));
            assertThat(updated.budgetUsd()).isEqualByComparingTo("0.01");
            var requestsBefore = llm.requests().size();
            send(setup, "Second question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);

            var stopped = lastEvent(ws, "turn.completed");
            assertThat(stopped.path("reason").asString()).isEqualTo("budget_exceeded");
            assertThat(stopped.path("error").path("message").asString())
                    .contains("budget of $0.01")
                    .contains("$0.017 spent");
            assertThat(llm.requests()).hasSize(requestsBefore);
            assertThat(session(setup).status()).isEqualTo("idle");

            assertThat(updateBudget(setup, BigDecimal.ZERO).budgetUsd()).isNull();
            llm.enqueueText("Third answer.");
            send(setup, "Third question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 3, TIMEOUT);
            assertThat(lastEvent(ws, "turn.completed").path("reason").asString())
                    .isEqualTo("completed");
        }
    }

    @Test
    void cachedTokensArePricedAndPrefixReuseIsRecorded() throws Exception {
        var setup = setUp(false);
        priceModel(
                setup, Map.of("inputPricePerMtok", 1000, "cachedInputPricePerMtok", 100, "outputPricePerMtok", 1000));
        llm.enqueueText("First answer.");
        llm.enqueueText("Title");

        try (var ws = viewer(setup)) {
            send(setup, "Hello", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
            llm.enqueueStream(
                    Duration.ZERO,
                    "{\"choices\":[{\"delta\":{\"content\":\"Second answer.\"}}]}",
                    "{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,"
                            + "\"prompt_tokens_details\":{\"cached_tokens\":10}}}",
                    "[DONE]");
            send(setup, "Second question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);

            var usage = usage(setup);
            assertThat(usage.estimatedCost()).isEqualByComparingTo("0.025");
            var step = usage.entries().stream()
                    .filter(entry -> entry.purpose().equals("step"))
                    .findFirst()
                    .orElseThrow();
            assertThat(step.calls()).isEqualTo(2);
            assertThat(step.promptTokens()).isEqualTo(24);
            assertThat(step.cachedPromptTokens()).isEqualTo(10);
            assertThat(step.cacheReportedPromptTokens()).isEqualTo(12);
            assertThat(step.costUsd()).isEqualByComparingTo("0.025");
            assertThat(step.prefixTokens()).isPositive();
            assertThat(step.prefixReuseTokens()).isPositive().isLessThan(step.prefixTokens());
            var title = usage.entries().stream()
                    .filter(entry -> entry.purpose().equals("title"))
                    .findFirst()
                    .orElseThrow();
            assertThat(title.cachedPromptTokens()).isNull();
            assertThat(title.cacheReportedPromptTokens()).isNull();
            assertThat(title.prefixTokens()).isNull();
            assertThat(title.prefixReuseTokens()).isNull();
        }
    }

    private void priceModel(Setup setup) {
        priceModel(setup, Map.of("inputPricePerMtok", 1000, "outputPricePerMtok", 1000));
    }

    private void priceModel(Setup setup, Map<String, Object> prices) {
        var provider = client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(ProviderDtos.ProviderResponse[].class)
                .returnResult()
                .getResponseBody()[0];
        client.patch()
                .uri(
                        "/api/v1/providers/{providerId}/models/{modelId}",
                        provider.id(),
                        provider.models().getFirst().id())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(prices)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private SessionDtos.SessionResponse updateBudget(Setup setup, BigDecimal budget) {
        return client.patch()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("budgetUsd", budget))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private SessionDtos.UsageResponse usage(Setup setup) {
        return client.get()
                .uri("/api/v1/sessions/{id}/usage", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(SessionDtos.UsageResponse.class)
                .returnResult()
                .getResponseBody();
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

    private static JsonNode lastEvent(GatewayTestClient ws, String kind) {
        return ws.events().stream()
                .filter(event -> eventKind(event).equals(kind))
                .map(event -> event.path("payload").path("event"))
                .reduce((first, second) -> second)
                .orElseThrow();
    }
}
