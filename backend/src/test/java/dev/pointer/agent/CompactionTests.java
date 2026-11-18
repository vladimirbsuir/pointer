package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CompactionTests extends AgentIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String AGENT_PROMPT = "You are Pointer, an AI software engineering agent working";
    private static final String CACHE_SAFE_INSTRUCTION = "do not call any tools";
    private static final String LEGACY_SYSTEM = "You summarize a conversation";
    private static final String LONG_ANSWER = "The parser keeps every token in a ring buffer. ".repeat(1_700);

    @Test
    void compactionOnTheSessionModelExtendsTheStepRequestAndReusesItsPrefix() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("Answer one");

        try (var ws = viewer(setup)) {
            send(setup, "First question", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            llm.enqueueText("SUMMARY-TEXT");
            compact(setup);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
            assertThat(lastItem(ws, "compaction_summary").path("trigger").asString())
                    .isEqualTo("manual");
        }

        var requests = llm.requests();
        var step = requests.get(requests.size() - 3);
        var compaction = requests.getLast();
        assertThat(compaction).contains(AGENT_PROMPT, CACHE_SAFE_INSTRUCTION, "First question", "Answer one");
        assertThat(compaction).doesNotContain(LEGACY_SYSTEM);
        assertThat(JSON.readTree(compaction).path("messages").get(0))
                .isEqualTo(JSON.readTree(step).path("messages").get(0));
        var row = usage(setup).entries().stream()
                .filter(entry -> entry.purpose().equals("compaction"))
                .findFirst()
                .orElseThrow();
        assertThat(row.prefixReuseTokens()).isPositive();
    }

    @Test
    void aToolCallInTheCacheSafeAnswerIsRetriedWithTheOldRequest() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("Answer one");

        try (var ws = viewer(setup)) {
            send(setup, "First question", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            llm.enqueueToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}");
            llm.enqueueText("SUMMARY-TEXT");
            compact(setup);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);

            var summary = lastItem(ws, "compaction_summary");
            assertThat(summary.path("text").asString()).isEqualTo("SUMMARY-TEXT");
            assertThat(summary.path("status").asString()).isEqualTo("completed");
        }

        var requests = llm.requests();
        assertThat(requests.get(requests.size() - 2)).contains(CACHE_SAFE_INSTRUCTION);
        assertThat(requests.getLast())
                .contains(LEGACY_SYSTEM, "First question")
                .doesNotContain(CACHE_SAFE_INSTRUCTION)
                .doesNotContain("\"tools\"");
    }

    @Test
    void theSoftTriggerCompactsAtATurnBoundaryWhenTheShorterContextPays() throws Exception {
        var secondTurn = secondTurnAfterALongAnswer("summary", true);

        assertThat(secondTurn.summary().path("trigger").asString()).isEqualTo("soft");
        assertThat(secondTurn.request())
                .contains("SOFT-SUMMARY", "Second question")
                .doesNotContain("ring buffer");
    }

    @Test
    void theSoftTriggerLeavesMaskingSessionsAndModelsWithoutACachedPriceAlone() throws Exception {
        assertThat(secondTurnAfterALongAnswer("masking", true).request()).contains("ring buffer");
        assertThat(secondTurnAfterALongAnswer("summary", false).request()).contains("ring buffer");
    }

    private SecondTurn secondTurnAfterALongAnswer(String strategy, boolean cachedPrice) throws Exception {
        var setup = setUp(false);
        priceModel(setup, cachedPrice);
        updateSession(setup, Map.of("contextStrategy", strategy));
        for (var call = 0; call < 3; call++) {
            llm.enqueueStream(
                    Duration.ZERO,
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_" + call
                            + "\",\"type\":\"function\",\"function\":{\"name\":\"no_such_tool\","
                            + "\"arguments\":\"{}\"}}]}}]}",
                    "{\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":10}}",
                    "[DONE]");
        }
        llm.enqueueStream(
                Duration.ZERO,
                "{\"choices\":[{\"delta\":{\"content\":" + JSON.writeValueAsString(LONG_ANSWER) + "}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":40000,\"completion_tokens\":20000}}",
                "[DONE]");

        try (var ws = viewer(setup)) {
            send(setup, "First question", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            var compacts = "summary".equals(strategy) && cachedPrice;
            if (compacts) {
                llm.enqueueText("SOFT-SUMMARY");
            }
            llm.enqueueText("Answer two");
            send(setup, "Second question", null);
            ws.await(message -> countEvents(ws, "turn.completed") == 2, TIMEOUT);
            return new SecondTurn(llm.requests().getLast(), compacts ? lastItem(ws, "compaction_summary") : null);
        }
    }

    private void priceModel(Setup setup, boolean cachedPrice) {
        var provider = client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(ProviderDtos.ProviderResponse[].class)
                .returnResult()
                .getResponseBody()[0];
        var body = new HashMap<String, Object>(
                Map.of("contextWindow", 100_000, "inputPricePerMtok", 1.0, "outputPricePerMtok", 0.4));
        if (cachedPrice) {
            body.put("cachedInputPricePerMtok", 0.1);
        }
        client.patch()
                .uri(
                        "/api/v1/providers/{id}/models/{modelId}",
                        provider.id(),
                        provider.models().getFirst().id())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private void compact(Setup setup) {
        client.post()
                .uri("/api/v1/sessions/{id}/compact", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isAccepted();
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

    private static JsonNode lastItem(GatewayTestClient ws, String type) {
        return ws.events().stream()
                .filter(event -> List.of("item.created", "item.updated", "item.completed")
                        .contains(eventKind(event)))
                .map(event -> event.path("payload").path("event").path("item"))
                .filter(item -> item.path("type").asString().equals(type))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private record SecondTurn(String request, JsonNode summary) {}
}
