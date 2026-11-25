package dev.pointer.container;

import dev.pointer.common.security.AuthPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/desktop")
@RequiredArgsConstructor
class DesktopViewController {

    private final DesktopViewService service;

    @GetMapping
    DesktopViewDtos.DesktopStatus status(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.status(principal.userId(), sessionId);
    }

    @PostMapping("/ticket")
    DesktopViewDtos.DesktopTicket ticket(AuthPrincipal principal, @PathVariable UUID sessionId) {
        return service.issueTicket(principal.userId(), sessionId);
    }
}
