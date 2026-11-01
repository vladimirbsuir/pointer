package dev.pointer.review;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_changes")
class FileChange extends BaseEntity {

    private UUID sessionId;
    private UUID turnId;
    private UUID itemId;
    private String path;
    private String changeKind;
    private String beforeContent;
    private String afterContent;
    private int linesAdded;
    private int linesRemoved;
    private Instant revertedAt;
    private Instant createdAt;

    protected FileChange() {}

    FileChange(
            UUID sessionId,
            UUID turnId,
            UUID itemId,
            String path,
            String changeKind,
            String beforeContent,
            String afterContent,
            int linesAdded,
            int linesRemoved,
            Instant now) {
        super(Ids.newId());
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.itemId = itemId;
        this.path = path;
        this.changeKind = changeKind;
        this.beforeContent = beforeContent;
        this.afterContent = afterContent;
        this.linesAdded = linesAdded;
        this.linesRemoved = linesRemoved;
        this.createdAt = now;
    }

    String getPath() {
        return path;
    }

    String getChangeKind() {
        return changeKind;
    }

    int getLinesAdded() {
        return linesAdded;
    }

    int getLinesRemoved() {
        return linesRemoved;
    }

    UUID getTurnId() {
        return turnId;
    }

    UUID getItemId() {
        return itemId;
    }

    String getBeforeContent() {
        return beforeContent;
    }

    String getAfterContent() {
        return afterContent;
    }

    Instant getRevertedAt() {
        return revertedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    boolean isReverted() {
        return revertedAt != null;
    }

    void markReverted(Instant now) {
        this.revertedAt = now;
    }
}
