package dev.pointer.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class MemoryDtos {

    private MemoryDtos() {}

    public record MemoryResponse(
            UUID id,
            UUID projectId,
            String scope,
            String kind,
            String name,
            String description,
            String content,
            String status,
            boolean pinned,
            String source,
            UUID sourceSessionId,
            int readCount,
            Instant lastReadAt,
            Instant createdAt,
            Instant updatedAt) {

        static MemoryResponse of(Memory memory) {
            return new MemoryResponse(
                    memory.id(),
                    memory.projectId(),
                    memory.scope(),
                    memory.kind(),
                    memory.name(),
                    memory.description(),
                    memory.content(),
                    memory.status(),
                    memory.pinned(),
                    memory.source(),
                    memory.sourceSessionId(),
                    memory.readCount(),
                    memory.lastReadAt(),
                    memory.createdAt(),
                    memory.updatedAt());
        }
    }

    public record CreateMemoryRequest(
            UUID projectId,

            @NotBlank @Pattern(regexp = "user|feedback|project|reference") String kind,

            @NotBlank @Pattern(regexp = Memory.NAME_PATTERN) String name,

            @NotBlank @Size(max = MemoryService.MAX_DESCRIPTION) String description,

            @NotBlank @Size(max = MemoryService.MAX_CONTENT) String content,
            Boolean pinned) {}

    public record RememberRequest(
            UUID projectId,
            @NotBlank @Size(max = MemoryService.MAX_CONTENT) String text) {}

    public record UpdateMemoryRequest(
            @Pattern(regexp = "user|feedback|project|reference") String kind,

            @Size(min = 1, max = MemoryService.MAX_DESCRIPTION) String description,

            @Size(min = 1, max = MemoryService.MAX_CONTENT) String content,
            Boolean pinned) {}
}
