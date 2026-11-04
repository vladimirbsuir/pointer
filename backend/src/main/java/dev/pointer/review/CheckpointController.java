package dev.pointer.review;

import dev.pointer.common.security.AuthPrincipal;
import dev.pointer.session.SessionWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class CheckpointController {

    private final SessionWriter writer;
    private final FileChangeService fileChanges;

    @PostMapping("/api/v1/sessions/{sessionId}/rewind")
    RewindResponse rewind(
            AuthPrincipal principal, @PathVariable UUID sessionId, @Valid @RequestBody RewindRequest request) {
        var turnIds = writer.turnsFrom(principal.userId(), sessionId, request.turnId());
        var restored = Boolean.TRUE.equals(request.restoreFiles())
                ? fileChanges.restoreTurns(principal.userId(), sessionId, turnIds, Boolean.TRUE.equals(request.force()))
                : List.<String>of();
        var result = writer.rewind(principal.userId(), sessionId, request.turnId());
        return new RewindResponse(result.turnIds(), restored, result.text());
    }

    record RewindRequest(@NotNull UUID turnId, Boolean restoreFiles, Boolean force) {}

    record RewindResponse(List<UUID> turnIds, List<String> restoredFiles, String text) {}
}
