package dev.pointer.tool;

import dev.pointer.common.security.AuthPrincipal;
import dev.pointer.project.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PermissionController {

    private final PermissionService permissions;
    private final ToolRegistry registry;
    private final ProjectService projects;

    PermissionController(PermissionService permissions, ToolRegistry registry, ProjectService projects) {
        this.permissions = permissions;
        this.registry = registry;
        this.projects = projects;
    }

    @GetMapping("/tools")
    List<ToolResponse> tools(AuthPrincipal principal) {
        return registry.available(principal.userId(), null, principal.deviceId()).stream()
                .map(tool -> new ToolResponse(
                        tool.getName(),
                        tool.getDescription(),
                        tool.getKind(),
                        tool.getModes(),
                        tool.getDefaultPolicy()))
                .toList();
    }

    @GetMapping("/permissions")
    PermissionService.PermissionsResponse global(AuthPrincipal principal) {
        return permissions.get(principal.userId(), null);
    }

    @PutMapping("/permissions")
    PermissionService.PermissionsResponse updateGlobal(
            AuthPrincipal principal, @Valid @RequestBody PermissionService.UpdatePermissionsRequest request) {
        return permissions.update(principal.userId(), null, request);
    }

    @GetMapping("/projects/{projectId}/permissions")
    PermissionService.PermissionsResponse project(AuthPrincipal principal, @PathVariable UUID projectId) {
        projects.owned(principal.userId(), projectId);
        return permissions.get(principal.userId(), projectId);
    }

    @PutMapping("/projects/{projectId}/permissions")
    PermissionService.PermissionsResponse updateProject(
            AuthPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody PermissionService.UpdatePermissionsRequest request) {
        projects.owned(principal.userId(), projectId);
        return permissions.update(principal.userId(), projectId, request);
    }

    record ToolResponse(String name, String description, String kind, List<String> modes, String defaultPolicy) {}
}
