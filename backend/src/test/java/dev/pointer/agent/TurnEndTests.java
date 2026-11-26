package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.session.ApprovalService;
import dev.pointer.session.SessionDtos;
import dev.pointer.session.SessionWriter;
import dev.pointer.session.Turn;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class TurnEndTests extends AgentIntegrationTest {

    @Autowired
    private SessionWriter writer;

    @Autowired
    private ApprovalService approvals;

    @Test
    void queuedMessageStartsTheNextTurnAfterTheStepLimit() {
        var setup = setUp(false);
        var userId = setup.auth().user().id();
        var first = writer.acceptUserMessage(userId, setup.sessionId(), message("First"), null);
        var queued = writer.acceptUserMessage(userId, setup.sessionId(), message("Second"), null);
        assertThat(queued.queued()).isTrue();

        var next = writer.finishTurn(userId, setup.sessionId(), first.turnId(), Turn.STEP_LIMIT, null);

        assertThat(next).isPresent();
        assertThat(sessionStatus(setup)).isEqualTo("running");
    }

    @Test
    void queuedMessageWaitsAfterTheBudgetIsExceeded() {
        var setup = setUp(false);
        var userId = setup.auth().user().id();
        var first = writer.acceptUserMessage(userId, setup.sessionId(), message("First"), null);
        writer.acceptUserMessage(userId, setup.sessionId(), message("Second"), null);

        var next = writer.finishTurn(
                userId,
                setup.sessionId(),
                first.turnId(),
                Turn.BUDGET_EXCEEDED,
                Map.of("code", "budget_exceeded", "message", "Budget reached"));

        assertThat(next).isEmpty();
        assertThat(sessionStatus(setup)).isEqualTo("idle");
    }

    @Test
    void approvalLeftPendingByAFinishedTurnCannotResumeTheSession() {
        var setup = setUp(false);
        var userId = setup.auth().user().id();
        var accepted = writer.acceptUserMessage(userId, setup.sessionId(), message("Hi"), null);
        var approvalId = approvals.request(userId, setup.sessionId(), accepted.itemId(), Map.of("command", "ls"));

        writer.finishTurn(userId, setup.sessionId(), accepted.turnId(), Turn.FAILED, Map.of("code", "internal_error"));

        client.post()
                .uri("/api/v1/sessions/{id}/approvals/{approvalId}", setup.sessionId(), approvalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("decision", "approved", "scope", "once"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(sessionStatus(setup)).isEqualTo("error");
    }

    private static SessionDtos.SendMessageRequest message(String text) {
        return new SessionDtos.SendMessageRequest(text, null, null, null);
    }
}
