package dev.pointer.project;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

    private UUID userId;
    private String name;
    private Instant archivedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected Project() {}

    Project(UUID userId, String name, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.name = name;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    void archive(boolean archived, Instant now) {
        this.archivedAt = archived ? now : null;
        this.updatedAt = now;
    }
}
