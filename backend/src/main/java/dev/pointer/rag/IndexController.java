package dev.pointer.rag;

import dev.pointer.common.security.AuthPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/index")
@RequiredArgsConstructor
class IndexController {

    private final IndexService service;

    @GetMapping
    IndexDtos.IndexStatusResponse status(AuthPrincipal principal, @PathVariable UUID projectId) {
        return service.status(principal.userId(), projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    IndexDtos.IndexStatusResponse start(
            AuthPrincipal principal,
            @PathVariable UUID projectId,
            @RequestBody(required = false) IndexDtos.StartIndexRequest request) {
        var rebuild = request != null && Boolean.TRUE.equals(request.rebuild());
        return service.start(principal.userId(), principal.deviceId(), projectId, rebuild);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthPrincipal principal, @PathVariable UUID projectId) {
        service.delete(principal.userId(), projectId);
    }
}
