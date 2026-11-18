package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

class ContextStrategyTests extends AgentIntegrationTest {

    @Test
    void newSessionsTakeTheDefaultStrategyAndSessionsCanChangeIt() {
        var setup = setUp(false);
        assertThat(session(setup).contextStrategy()).isEqualTo("summary");

        client.put()
                .uri("/api/v1/me/settings")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("contextStrategyDefault", "hybrid"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .value(settings -> assertThat(
                                settings.path("contextStrategyDefault").asString())
                        .isEqualTo("hybrid"));
        var created = client.post()
                .uri("/api/v1/projects/{id}/sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("mode", "ask"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CREATED)
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created.contextStrategy()).isEqualTo("hybrid");

        assertThat(update(setup, Map.of("contextStrategy", "masking")).contextStrategy())
                .isEqualTo("masking");
        client.patch()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("contextStrategy", "trim"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void maskingDoesNotSummarizeAtTheUsualLimitButHybridDoes() throws Exception {
        assertThat(secondRequestAfterANearlyFullTurn("masking")).contains("First question");
        assertThat(secondRequestAfterANearlyFullTurn("hybrid"))
                .contains("AUTO-SUMMARY")
                .doesNotContain("First question");
    }

    private String secondRequestAfterANearlyFullTurn(String strategy) throws Exception {
        var setup = setUp(false);
        update(setup, Map.of("contextStrategy", strategy));
        llm.enqueueStream(
                Duration.ZERO,
                "{\"choices\":[{\"delta\":{\"content\":\"Long answer\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":7000,\"completion_tokens\":10}}",
                "[DONE]");

        try (var ws = viewer(setup)) {
            send(setup, "First question", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);
            if ("hybrid".equals(strategy)) {
                llm.enqueueText("AUTO-SUMMARY");
            }
            llm.enqueueText("Answer two");
            send(setup, "Second question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
        }
        return llm.requests().getLast();
    }

    private SessionDtos.SessionResponse update(Setup setup, Map<String, Object> body) {
        return client.patch()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(SessionDtos.SessionResponse.class)
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
}
