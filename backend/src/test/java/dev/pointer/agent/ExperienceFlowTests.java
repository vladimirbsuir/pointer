package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

class ExperienceFlowTests extends AgentIntegrationTest {

    private static final String PLAN_ARGUMENTS =
            "{\"summary\":\"Add a greeting\",\"steps\":[{\"title\":\"Edit README\",\"details\":\"Add a line\"}]}";

    @Test
    void approvedPlanSwitchesToAgentModeAndStartsImplementation() throws Exception {
        var setup = setUp(false, "plan");
        llm.enqueueToolCall("call_1", "propose_plan", PLAN_ARGUMENTS);

        try (var ws = viewer(setup)) {
            send(setup, "Plan a greeting", null).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            var plan = lastItem(ws, "plan");
            assertThat(plan.path("approval").asString()).isEqualTo("pending");
            assertThat(plan.path("summary").asString()).isEqualTo("Add a greeting");
            assertThat(plan.path("steps").get(0).path("title").asString()).isEqualTo("Edit README");
            assertThat(llm.requests().getFirst()).contains("\"name\":\"propose_plan\"");

            llm.enqueueText("Implemented.");
            approve(setup, plan.path("id").asString()).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);

            assertThat(lastItem(ws, "plan").path("approval").asString()).isEqualTo("approved");
            approve(setup, plan.path("id").asString()).expectStatus().isEqualTo(HttpStatus.CONFLICT);
        }
        assertThat(session(setup).mode()).isEqualTo("agent");
        var implementation = llm.requests().getLast();
        assertThat(implementation)
                .contains("The plan is approved")
                .contains("The plan was shown to the user")
                .doesNotContain("\"name\":\"propose_plan\",\"description\"");
    }

    @Test
    void newMessageRejectsPendingPlan() throws Exception {
        var setup = setUp(false, "plan");
        llm.enqueueToolCall("call_1", "propose_plan", PLAN_ARGUMENTS);

        try (var ws = viewer(setup)) {
            send(setup, "Plan a greeting", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            llm.enqueueText("Sure.");
            send(setup, "Use a different file", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
            assertThat(lastItem(ws, "plan").path("approval").asString()).isEqualTo("rejected");
        }
    }

    @Test
    void manualCompactionReplacesHistoryWithSummary() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("Answer one");

        try (var ws = viewer(setup)) {
            send(setup, "First question", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            llm.enqueueText("SUMMARY-TEXT");
            client.post()
                    .uri("/api/v1/sessions/{id}/compact", setup.sessionId())
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .isAccepted();
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);

            var summary = lastItem(ws, "compaction_summary");
            assertThat(summary.path("text").asString()).isEqualTo("SUMMARY-TEXT");
            assertThat(summary.path("replacedItemCount").asInt()).isEqualTo(2);
            assertThat(llm.requests().getLast()).contains("Summarize the conversation");

            llm.enqueueText("Answer two");
            send(setup, "Second question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 3, TIMEOUT);
        }
        assertThat(llm.requests().getLast())
                .contains("SUMMARY-TEXT")
                .contains("Second question")
                .doesNotContain("First question");
    }

    @Test
    void conversationIsCompactedAutomaticallyWhenContextIsNearlyFull() throws Exception {
        var setup = setUp(false);
        llm.enqueueStream(
                Duration.ZERO,
                "{\"choices\":[{\"delta\":{\"content\":\"Long answer\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":7000,\"completion_tokens\":10}}",
                "[DONE]");

        try (var ws = viewer(setup)) {
            send(setup, "First question", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            llm.enqueueText("AUTO-SUMMARY");
            llm.enqueueText("Answer two");
            send(setup, "Second question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);

            assertThat(lastItem(ws, "compaction_summary").path("text").asString())
                    .isEqualTo("AUTO-SUMMARY");
        }
        assertThat(llm.requests().getLast())
                .contains("AUTO-SUMMARY")
                .contains("Second question")
                .doesNotContain("First question");
        assertThat(session(setup).contextTokens()).isLessThan(6400);
    }

    @Test
    void compactionIsRejectedWhileTurnIsRunning() throws Exception {
        var setup = setUp(false);
        llm.enqueueSilence(Duration.ofSeconds(5));

        try (var ws = viewer(setup)) {
            send(setup, "Question", null);
            ws.await(
                    message -> eventKind(message).equals("status.changed")
                            && message.path("payload")
                                    .path("event")
                                    .path("status")
                                    .asString()
                                    .equals("running"),
                    TIMEOUT);
            client.post()
                    .uri("/api/v1/sessions/{id}/compact", setup.sessionId())
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.CONFLICT);
            client.post()
                    .uri("/api/v1/sessions/{id}/cancel", setup.sessionId())
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .isAccepted();
            ws.awaitEvent("turn.completed", TIMEOUT);
        }
    }

    private GatewayTestClient viewer(Setup setup) throws Exception {
        var ws = GatewayTestClient.connect(port, setup.auth().accessToken(), setup.deviceId(), List.of("viewer"));
        ws.subscribe(setup.sessionId(), 0);
        ws.await(message -> GatewayTestClient.type(message).equals("session.subscribed"), TIMEOUT);
        return ws;
    }

    private RestTestClient.ResponseSpec approve(Setup setup, String itemId) {
        return client.post()
                .uri("/api/v1/sessions/{id}/plans/{itemId}/approve", setup.sessionId(), itemId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange();
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

    private static void awaitTitle(GatewayTestClient ws) throws Exception {
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

    private static JsonNode lastItem(GatewayTestClient ws, String type) {
        return ws.events().stream()
                .filter(event -> List.of("item.created", "item.updated", "item.completed")
                        .contains(eventKind(event)))
                .map(event -> event.path("payload").path("event").path("item"))
                .filter(item -> item.path("type").asString().equals(type))
                .reduce((first, second) -> second)
                .orElseThrow();
    }
}
