package dev.pointer.memory;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/v1/memories")
@RequiredArgsConstructor
class MemoryController {

    private final MemoryService service;

    @GetMapping
    List<MemoryDtos.MemoryResponse> list(
            AuthPrincipal principal,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = Memory.ACTIVE) String status) {
        return service.list(principal.userId(), projectId, status).stream()
                .map(MemoryDtos.MemoryResponse::of)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    MemoryDtos.MemoryResponse create(
            AuthPrincipal principal, @Valid @RequestBody MemoryDtos.CreateMemoryRequest request) {
        var memory = service.create(
                principal.userId(),
                request.projectId(),
                new MemoryService.Draft(request.kind(), request.name(), request.description(), request.content()),
                "user",
                principal.deviceId());
        if (Boolean.TRUE.equals(request.pinned())) {
            memory = service.update(
                    principal.userId(),
                    memory.id(),
                    new MemoryService.Patch(null, null, null, true),
                    principal.deviceId());
        }
        return MemoryDtos.MemoryResponse.of(memory);
    }

    @PostMapping("/remember")
    @ResponseStatus(HttpStatus.CREATED)
    MemoryDtos.MemoryResponse remember(
            AuthPrincipal principal, @Valid @RequestBody MemoryDtos.RememberRequest request) {
        return MemoryDtos.MemoryResponse.of(
                service.remember(principal.userId(), request.projectId(), request.text(), "user"));
    }

    @PatchMapping("/{memoryId}")
    MemoryDtos.MemoryResponse update(
            AuthPrincipal principal,
            @PathVariable UUID memoryId,
            @Valid @RequestBody MemoryDtos.UpdateMemoryRequest request) {
        return MemoryDtos.MemoryResponse.of(service.update(
                principal.userId(),
                memoryId,
                new MemoryService.Patch(request.kind(), request.description(), request.content(), request.pinned()),
                principal.deviceId()));
    }

    @DeleteMapping("/{memoryId}")
    MemoryDtos.MemoryResponse delete(AuthPrincipal principal, @PathVariable UUID memoryId) {
        return MemoryDtos.MemoryResponse.of(service.delete(principal.userId(), memoryId));
    }

    @PostMapping("/{memoryId}/undo")
    MemoryDtos.MemoryResponse undo(AuthPrincipal principal, @PathVariable UUID memoryId) {
        return MemoryDtos.MemoryResponse.of(service.undo(principal.userId(), memoryId));
    }

    @PostMapping("/{memoryId}/accept")
    MemoryDtos.MemoryResponse accept(AuthPrincipal principal, @PathVariable UUID memoryId) {
        return MemoryDtos.MemoryResponse.of(service.accept(principal.userId(), memoryId, principal.deviceId()));
    }

    @PostMapping("/{memoryId}/dismiss")
    MemoryDtos.MemoryResponse dismiss(AuthPrincipal principal, @PathVariable UUID memoryId) {
        return MemoryDtos.MemoryResponse.of(service.dismiss(principal.userId(), memoryId));
    }
}
