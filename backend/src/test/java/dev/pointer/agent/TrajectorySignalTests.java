package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.testing.GatewayTestClient;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

class TrajectorySignalTests extends AgentIntegrationTest {

    @Test
    void everyStepOfATurnRecordsItsSignalsAndScore() throws Exception {
        var setup = setUp(false);
        llm.enqueueToolCall("call_1", "no_such_tool", "{\"path\": \"a.txt\"}");
        llm.enqueueToolCall("call_2", "no_such_tool", "{\"path\":\"a.txt\"}");
        llm.enqueueText("Done");

        try (var ws = viewer(setup)) {
            send(setup, "Look around", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
        }

        var turnId = turnId(setup);
        var rows = signals(setup, turnId);
        assertThat(rows).extracting(row -> row.path("step").asInt()).containsExactly(0, 1, 2);
        assertThat(rows).extracting(row -> row.path("modelLabel").asString()).containsOnly("fake-model");
        assertThat(rows)
                .extracting(row -> row.path("signals").path("malformed_call").asInt())
                .containsExactly(1, 2, 2);
        assertThat(rows)
                .extracting(row -> row.path("signals").path("repeat_call").asInt())
                .containsExactly(0, 1, 1);
        assertThat(rows.get(1).path("score").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(rows).allMatch(row -> !row.path("escalated").asBoolean());

        var stranger = setUp(false);
        client.get()
                .uri("/api/v1/sessions/{id}/turns/{turnId}/signals", setup.sessionId(), turnId)
                .header(HttpHeaders.AUTHORIZATION, bearer(stranger.auth()))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void aToolOutsideTheModeIsReportedAsUnavailableAndCountsAsMalformed() throws Exception {
        var setup = setUp(false, "ask");
        llm.enqueueToolCall("call_1", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");
        llm.enqueueText("Done");

        try (var ws = viewer(setup)) {
            send(setup, "Write a file", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
        }

        var call = items(setup).stream()
                .filter(item -> item.path("type").asString().equals("tool_call"))
                .findFirst()
                .orElseThrow();
        assertThat(call.path("toolStatus").asString()).isEqualTo("denied");
        assertThat(call.path("error").path("code").asString()).isEqualTo("tool_unavailable");
        var rows = signals(setup, turnId(setup));
        assertThat(rows.getFirst().path("signals").path("malformed_call").asInt())
                .isEqualTo(1);
        assertThat(rows.getFirst().path("signals").path("denied").asInt()).isZero();
    }

    private UUID turnId(Setup setup) {
        return UUID.fromString(items(setup).getFirst().path("turnId").asString());
    }

    private List<JsonNode> signals(Setup setup, UUID turnId) {
        return list(client.get()
                .uri("/api/v1/sessions/{id}/turns/{turnId}/signals", setup.sessionId(), turnId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody());
    }

    private List<JsonNode> items(Setup setup) {
        return list(client.get()
                .uri("/api/v1/sessions/{id}/items", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody());
    }

    private static List<JsonNode> list(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    private GatewayTestClient viewer(Setup setup) throws Exception {
        var ws = GatewayTestClient.connect(port, setup.auth().accessToken(), setup.deviceId(), List.of("viewer"));
        ws.subscribe(setup.sessionId(), 0);
        ws.await(message -> GatewayTestClient.type(message).equals("session.subscribed"), TIMEOUT);
        return ws;
    }
}
