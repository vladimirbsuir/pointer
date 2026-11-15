package dev.pointer.memory;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record Memory(
        UUID id,
        UUID userId,
        UUID projectId,
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

    static final String ACTIVE = "active";
    static final String SUGGESTED = "suggested";
    static final String DISMISSED = "dismissed";
    static final String DELETED = "deleted";
    static final String SCOPE_USER = "user";
    static final String SCOPE_PROJECT = "project";
    static final Set<String> KINDS = Set.of("user", "feedback", "project", "reference");
    static final String NAME_PATTERN = "^[a-z0-9][a-z0-9-]{1,63}$";

    String scope() {
        return projectId == null ? SCOPE_USER : SCOPE_PROJECT;
    }

    String embeddingText() {
        return name + "\n" + description + "\n\n" + content;
    }
}
