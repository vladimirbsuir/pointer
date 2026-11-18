package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "pointer.escalation.policy=nudge")
class NudgeTests extends EscalationTests {

    @Test
    void theFirstWarningNudgesTheFastModelAndOnlyAHigherScoreEscalates() throws Exception {
        var setup = escalatingSession("agent");
        llm.enqueueToolCall("call_1", "no_such_tool", "{\"a\":1}");
        llm.enqueueText("Answered without the tool.");
        llm.enqueueText("Title");

        try (var ws = viewer(setup)) {
            send(setup, "Use the tool", null);
            ws.awaitEvent("turn.completed", TIMEOUT);
            awaitTitle(ws);

            var nudge = lastItem(ws, "model_nudged");
            assertThat(nudge.path("model").asString()).isEqualTo("fast-model");
            assertThat(nudge.path("text").asString()).startsWith("Note from Pointer");
            assertThat(ws.events().stream()
                            .map(event -> event.path("payload")
                                    .path("event")
                                    .path("item")
                                    .path("type")
                                    .asString()))
                    .doesNotContain("model_escalated");
        }

        var requests = llm.requests();
        assertThat(requestedModels(requests.subList(0, 2))).containsExactly("fast-model", "fast-model");
        assertThat(requests.get(1)).contains("Note from Pointer about your last steps");
        assertThat(signals(setup, firstTurnId(setup)))
                .extracting(row -> row.path("nudged").asBoolean())
                .containsExactly(true, false);
    }
}
