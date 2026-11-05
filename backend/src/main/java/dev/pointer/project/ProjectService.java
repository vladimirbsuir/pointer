package dev.pointer.project;

import dev.pointer.common.error.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projects;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(UUID userId) {
        return projects.findByUserIdOrderByCreatedAt(userId).stream()
                .map(ProjectService::toResponse)
                .toList();
    }

    @Transactional
    public ProjectResponse create(UUID userId, CreateProjectRequest request) {
        var project = new Project(userId, request.name().strip(), clock.instant());
        if (request.repository() != null && !request.repository().isBlank()) {
            project.changeRepository(request.repository().strip(), clock.instant());
        }
        return toResponse(projects.save(project));
    }

    @Transactional
    public ProjectResponse update(UUID userId, UUID projectId, UpdateProjectRequest request) {
        var project = owned(userId, projectId);
        if (request.name() != null) {
            project.rename(request.name().strip(), clock.instant());
        }
        if (request.repository() != null) {
            project.changeRepository(
                    request.repository().isBlank() ? null : request.repository().strip(), clock.instant());
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
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getRepository(),
                project.getArchivedAt(),
                project.getCreatedAt());
    }

    public static final String REPOSITORY_PATTERN = "^$|^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$";

    public record ProjectResponse(UUID id, String name, String repository, Instant archivedAt, Instant createdAt) {}

    public record CreateProjectRequest(
            @NotBlank @Size(max = 200) String name,

            @Size(max = 200) @Pattern(regexp = REPOSITORY_PATTERN) String repository) {}

    public record UpdateProjectRequest(
            @Size(min = 1, max = 200) String name,

            @Size(max = 200) @Pattern(regexp = REPOSITORY_PATTERN) String repository,

            Boolean archived) {}
}
