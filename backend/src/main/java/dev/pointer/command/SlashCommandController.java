package dev.pointer.command;

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
@RequestMapping("/api/v1/commands")
@RequiredArgsConstructor
class SlashCommandController {

    private final SlashCommandService service;

    @GetMapping
    List<SlashCommandService.CommandResponse> list(AuthPrincipal principal) {
        return service.list(principal.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SlashCommandService.CommandResponse create(
            AuthPrincipal principal, @Valid @RequestBody SlashCommandService.CommandRequest request) {
        return service.create(principal.userId(), request);
    }

    @PutMapping("/{commandId}")
    SlashCommandService.CommandResponse update(
            AuthPrincipal principal,
            @PathVariable UUID commandId,
            @Valid @RequestBody SlashCommandService.CommandRequest request) {
        return service.update(principal.userId(), commandId, request);
    }

    @DeleteMapping("/{commandId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthPrincipal principal, @PathVariable UUID commandId) {
        service.delete(principal.userId(), commandId);
    }
}
