package dev.pointer.project;

import dev.pointer.common.error.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final Clock clock;

    ProjectService(ProjectRepository projects, Clock clock) {
        this.projects = projects;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(UUID userId) {
        return projects.findByUserIdOrderByCreatedAt(userId).stream()
                .map(ProjectService::toResponse)
                .toList();
    }

    @Transactional
    public ProjectResponse create(UUID userId, CreateProjectRequest request) {
        return toResponse(projects.save(new Project(userId, request.name().strip(), clock.instant())));
    }

    @Transactional
    public ProjectResponse update(UUID userId, UUID projectId, UpdateProjectRequest request) {
        var project = owned(userId, projectId);
        if (request.name() != null) {
            project.rename(request.name().strip(), clock.instant());
        }
        if (request.archived() != null) {
            project.archive(request.archived(), clock.instant());
        }
        return toResponse(projects.save(project));
    }

    @Transactional
    public void delete(UUID userId, UUID projectId) {
        projects.delete(owned(userId, projectId));
    }

    @Transactional(readOnly = true)
    public Project owned(UUID userId, UUID projectId) {
        return projects.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> ApiException.notFound("project_not_found", "Project does not exist"));
    }

    private static ProjectResponse toResponse(Project project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getArchivedAt(), project.getCreatedAt());
    }

    public record ProjectResponse(UUID id, String name, Instant archivedAt, Instant createdAt) {}

    public record CreateProjectRequest(
            @NotBlank @Size(max = 200) String name) {}

    public record UpdateProjectRequest(
            @Size(min = 1, max = 200) String name, Boolean archived) {}
}
