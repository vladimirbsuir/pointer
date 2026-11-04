package dev.pointer.worktree;

import dev.pointer.common.security.AuthPrincipal;
import dev.pointer.session.SessionDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/worktree")
@RequiredArgsConstructor
class WorktreeController {

    private final WorktreeService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SessionDtos.SessionResponse create(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.create(principal.userId(), sessionId);
    }

    @GetMapping
    Map<String, Object> status(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.status(principal.userId(), sessionId);
    }

    @PostMapping("/merge")
    SessionDtos.SessionResponse merge(
            AuthPrincipal principal, @PathVariable UUID sessionId, @Valid @RequestBody MergeRequest request) {
        return service.merge(principal.userId(), sessionId, request.message());
    }

    @PostMapping("/discard")
    SessionDtos.SessionResponse discard(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.discard(principal.userId(), sessionId);
    }

    record MergeRequest(@Size(max = 500) String message) {}
}
