package dev.pointer.agent;

import dev.pointer.common.security.AuthPrincipal;
import dev.pointer.session.SessionDtos;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
class AgentController {

    private final AgentRunner runner;

    AgentController(AgentRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SessionDtos.SendMessageResponse send(
            AuthPrincipal principal,
            @PathVariable UUID sessionId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SessionDtos.SendMessageRequest request) {
        return runner.send(principal.userId(), sessionId, request, idempotencyKey);
    }

    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void cancel(AuthPrincipal principal, @PathVariable UUID sessionId) {
        runner.cancel(principal.userId(), sessionId);
    }
}
