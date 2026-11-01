package dev.pointer.session;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SessionController {

    private final SessionService service;
    private final ApprovalService approvals;

    SessionController(SessionService service, ApprovalService approvals) {
        this.service = service;
        this.approvals = approvals;
    }

    @PostMapping("/sessions/{sessionId}/approvals/{approvalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolveApproval(
            AuthPrincipal principal,
            @PathVariable UUID sessionId,
            @PathVariable UUID approvalId,
            @Valid @RequestBody ApprovalService.ResolveApprovalRequest request) {
        approvals.resolve(principal.userId(), sessionId, approvalId, request);
    }

    @GetMapping("/projects/{projectId}/sessions")
    List<SessionDtos.SessionResponse> list(AuthPrincipal principal, @PathVariable UUID projectId) {
        return service.list(principal.userId(), projectId);
    }

    @PostMapping("/projects/{projectId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    SessionDtos.SessionResponse create(
            AuthPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody SessionDtos.CreateSessionRequest request) {
        return service.create(principal.userId(), principal.deviceId(), projectId, request);
    }

    @GetMapping("/sessions/{sessionId}")
    SessionDtos.SessionResponse get(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.get(principal.userId(), sessionId);
    }

    @PatchMapping("/sessions/{sessionId}")
    SessionDtos.SessionResponse update(
            AuthPrincipal principal,
            @PathVariable UUID sessionId,
            @Valid @RequestBody SessionDtos.UpdateSessionRequest request) {
        return service.update(principal.userId(), sessionId, request);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthPrincipal principal, @PathVariable UUID sessionId) {
        service.delete(principal.userId(), sessionId);
    }

    @GetMapping("/sessions/{sessionId}/items")
    List<Map<String, Object>> items(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.items(principal.userId(), sessionId);
    }

    @GetMapping("/sessions/{sessionId}/events")
    List<SessionDtos.SessionEventResponse> events(
            AuthPrincipal principal, @PathVariable UUID sessionId, @RequestParam(defaultValue = "0") long afterSeq) {
        return service.events(principal.userId(), sessionId, afterSeq);
    }
}
