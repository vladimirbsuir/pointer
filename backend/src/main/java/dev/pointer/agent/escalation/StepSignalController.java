package dev.pointer.agent.escalation;

import dev.pointer.common.security.AuthPrincipal;
import dev.pointer.session.SessionService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/turns/{turnId}/signals")
@RequiredArgsConstructor
class StepSignalController {

    private final SessionService sessions;
    private final StepSignalLog stepSignals;

    @GetMapping
    List<StepSignalLog.StepSignals> signals(
            AuthPrincipal principal, @PathVariable UUID sessionId, @PathVariable UUID turnId) {
        sessions.owned(principal.userId(), sessionId);
        return stepSignals.turn(sessionId, turnId);
    }
}
