package dev.pointer.review;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/changes")
@RequiredArgsConstructor
class ReviewController {

    private final FileChangeService service;

    @GetMapping
    List<FileChangeService.ChangedFile> list(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.list(principal.userId(), sessionId);
    }

    @GetMapping("/file")
    FileChangeService.FileDiff diff(AuthPrincipal principal, @PathVariable UUID sessionId, @RequestParam String path) {
        return service.diff(principal.userId(), sessionId, path);
    }

    @PostMapping("/revert")
    FileChangeService.ChangedFile revert(
            AuthPrincipal principal,
            @PathVariable UUID sessionId,
            @Valid @RequestBody FileChangeService.RevertRequest request) {
        return service.revert(principal.userId(), sessionId, request.path(), Boolean.TRUE.equals(request.force()));
    }
}
