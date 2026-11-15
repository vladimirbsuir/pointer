package dev.pointer.automation;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/automation")
@RequiredArgsConstructor
class AutomationController {

    private final AutomationService service;

    @GetMapping
    AutomationDtos.AutomationResponse get(AuthPrincipal principal, @PathVariable UUID projectId) {
        return service.get(principal.userId(), projectId);
    }

    @PutMapping
    AutomationDtos.AutomationResponse save(
            AuthPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody AutomationDtos.SaveAutomationRequest request) {
        return service.save(principal.userId(), projectId, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthPrincipal principal, @PathVariable UUID projectId) {
        service.delete(principal.userId(), projectId);
    }

    @GetMapping("/runs")
    List<AutomationDtos.RunResponse> runs(AuthPrincipal principal, @PathVariable UUID projectId) {
        return service.runs(principal.userId(), projectId);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    AutomationDtos.RunResponse start(
            AuthPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody AutomationDtos.StartRunRequest request) {
        return service.startManual(principal.userId(), projectId, request.issueNumber(), AutomationRun.TRIGGER_DESKTOP);
    }

    @PostMapping("/runs/{runId}/cancel")
    AutomationDtos.RunResponse cancel(AuthPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID runId) {
        return service.cancel(principal.userId(), projectId, runId);
    }
}
