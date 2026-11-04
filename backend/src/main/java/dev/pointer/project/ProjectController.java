package dev.pointer.project;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
class ProjectController {

    private final ProjectService service;

    @GetMapping
    List<ProjectService.ProjectResponse> list(AuthPrincipal principal) {
        return service.list(principal.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectService.ProjectResponse create(
            AuthPrincipal principal, @Valid @RequestBody ProjectService.CreateProjectRequest request) {
        return service.create(principal.userId(), request);
    }

    @PatchMapping("/{projectId}")
    ProjectService.ProjectResponse update(
            AuthPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectService.UpdateProjectRequest request) {
        return service.update(principal.userId(), projectId, request);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthPrincipal principal, @PathVariable UUID projectId) {
        service.delete(principal.userId(), projectId);
    }
}
