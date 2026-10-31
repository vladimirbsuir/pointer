package dev.pointer.session;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class AgentSession extends BaseEntity {

    public static final String DEFAULT_TITLE = "New session";

    private UUID userId;
    private UUID projectId;
    private UUID executorDeviceId;
    private String title;
    private boolean titleGenerated;
    private String mode;
    private UUID modelId;
    private String status;
    private boolean autoApprove;

    @Column(updatable = false)
    private long lastSeq;

    @Column(updatable = false)
    private long lastPosition;

    private int contextTokens;
    private long totalInputTokens;
    private long totalOutputTokens;
    private Instant archivedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected AgentSession() {}

    AgentSession(
            UUID userId, UUID projectId, UUID executorDeviceId, String title, String mode, UUID modelId, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.projectId = projectId;
        this.executorDeviceId = executorDeviceId;
        this.title = title == null ? DEFAULT_TITLE : title;
        this.titleGenerated = title == null;
        this.mode = mode;
        this.modelId = modelId;
        this.status = SessionStatus.IDLE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getExecutorDeviceId() {
        return executorDeviceId;
    }

    public String getTitle() {
        return title;
    }

    public boolean isTitleGenerated() {
        return titleGenerated;
    }

    public String getMode() {
        return mode;
    }

    public UUID getModelId() {
        return modelId;
    }

    public String getStatus() {
        return status;
    }

    public boolean isAutoApprove() {
        return autoApprove;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public int getContextTokens() {
        return contextTokens;
    }

    public long getTotalInputTokens() {
        return totalInputTokens;
    }

    public long getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void rename(String title, boolean generated, Instant now) {
        this.title = title;
        this.titleGenerated = generated;
        this.updatedAt = now;
    }

    void changeMode(String mode, Instant now) {
        this.mode = mode;
        this.updatedAt = now;
    }

    void changeModel(UUID modelId, Instant now) {
        this.modelId = modelId;
        this.updatedAt = now;
    }

    void changeAutoApprove(boolean autoApprove, Instant now) {
        this.autoApprove = autoApprove;
        this.updatedAt = now;
    }

    void archive(boolean archived, Instant now) {
        this.archivedAt = archived ? now : null;
        this.updatedAt = now;
    }

    void changeStatus(String status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    void recordUsage(int contextTokens, long inputTokens, long outputTokens, Instant now) {
        this.contextTokens = contextTokens;
        this.totalInputTokens += inputTokens;
        this.totalOutputTokens += outputTokens;
        this.updatedAt = now;
    }
}
