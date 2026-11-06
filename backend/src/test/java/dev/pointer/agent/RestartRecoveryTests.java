package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.session.Item;
import dev.pointer.session.SessionDtos;
import dev.pointer.session.SessionWriter;
import dev.pointer.testing.GatewayTestClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RestartRecoveryTests extends AgentIntegrationTest {

    @Autowired
    private SessionWriter writer;

    @Test
    void turnLeftRunningByARestartIsAnnouncedAsInterrupted() throws Exception {
        var setup = setUp(false);
        var userId = setup.auth().user().id();
        var accepted = writer.acceptUserMessage(
                userId, setup.sessionId(), new SessionDtos.SendMessageRequest("Hi", null, null, null), null);
        var answer = writer.createItem(
                userId, setup.sessionId(), accepted.turnId(), Item.ASSISTANT_MESSAGE, Map.of("text", "Partial"));
        assertThat(sessionStatus(setup)).isEqualTo("running");

        try (var ws =
                GatewayTestClient.connect(port, setup.auth().accessToken(), setup.deviceId(), List.of("viewer"))) {
            ws.subscribe(setup.sessionId(), 0);
            ws.awaitEvent("item.created", TIMEOUT);

            assertThat(writer.recoverInterrupted()).isPositive();

            var completed =
                    ws.awaitEvent("turn.completed", TIMEOUT).path("payload").path("event");
            assertThat(completed.path("turnId").asString())
                    .isEqualTo(accepted.turnId().toString());
            assertThat(completed.path("reason").asString()).isEqualTo("interrupted");
            assertThat(completed.path("error").path("code").asString()).isEqualTo("interrupted");
            var status = ws.await(
                    message -> "status.changed".equals(eventKind(message))
                            && "idle"
                                    .equals(message.path("payload")
                                            .path("event")
                                            .path("status")
                                            .asString()),
                    TIMEOUT);
            assertThat(status).isNotNull();
            assertThat(ws.events().stream()
                            .filter(event -> "item.completed".equals(eventKind(event)))
                            .map(event -> event.path("payload").path("event").path("item"))
                            .filter(item -> item.path("id").asString().equals(String.valueOf(answer.get("id"))))
                            .map(item -> item.path("status").asString()))
                    .containsExactly("cancelled");
        }
        assertThat(sessionStatus(setup)).isEqualTo("idle");
    }
}
