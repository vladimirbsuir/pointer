package dev.pointer.container;

import dev.pointer.common.error.ApiException;
import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
class RemoteSessionController {

    private final RemoteSessionService service;

    @GetMapping("/remote")
    RemoteDtos.RemoteStatus status(AuthPrincipal principal) {
        return service.status(principal.userId());
    }

    @GetMapping("/github/repositories")
    List<RemoteDtos.RepositoryResponse> repositories(AuthPrincipal principal) {
        return service.repositories(principal.userId());
    }

    @PostMapping("/projects/{projectId}/remote-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    RemoteDtos.RemoteSessionResponse create(
            AuthPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody RemoteDtos.CreateRemoteSessionRequest request) {
        return service.create(principal.userId(), projectId, request);
    }

    @GetMapping("/projects/{projectId}/remote-sessions")
    List<RemoteDtos.RemoteWorkspaceResponse> forProject(AuthPrincipal principal, @PathVariable UUID projectId) {
        return service.forProject(principal.userId(), projectId);
    }

    @GetMapping("/sessions/{sessionId}/remote")
    RemoteDtos.RemoteWorkspaceResponse forSession(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.forSession(principal.userId(), sessionId)
                .orElseThrow(
                        () -> ApiException.notFound("remote_session_not_found", "The session is not on the server"));
    }

    @PostMapping("/sessions/{sessionId}/remote/pull-request")
    RemoteDtos.PullRequestResponse pullRequest(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.openPullRequest(principal.userId(), sessionId);
    }

    @GetMapping("/sessions/{sessionId}/remote/files")
    RemoteDtos.RemoteFileSuggestions files(
            AuthPrincipal principal,
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "") @Size(max = 200) String query) {
        return service.suggestFiles(principal.userId(), sessionId, query);
    }

    @GetMapping("/sessions/{sessionId}/remote/file")
    RemoteDtos.RemoteFile file(
            AuthPrincipal principal,
            @PathVariable UUID sessionId,
            @RequestParam @NotBlank @Size(max = 1000) String path) {
        return service.readFile(principal.userId(), sessionId, path);
    }
}
