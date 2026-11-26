package dev.pointer.container;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "remote_workspaces")
@Getter
public class RemoteWorkspace extends BaseEntity {

    static final String QUEUED = "queued";
    static final String STARTING = "starting";
    static final String PREPARING = "preparing";
    static final String READY = "ready";
    static final String STOPPED = "stopped";
    static final String FAILED = "failed";
    static final String SESSION = "session";
    static final String SANDBOX = "sandbox";

    private UUID sessionId;
    private UUID userId;
    private UUID deviceId;
    private String kind;
    private String repository;
    private String branch;
    private String baseBranch;
    private String containerName;
    private String volumeName;
    private String status;
    private String error;
    private String pushedCommit;
    private String pullRequestUrl;
    private byte[] desktopViewSecret;
    private Instant syncedAt;
    private Integer syncedFiles;
    private Long syncedBytes;
    private Instant lastActiveAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected RemoteWorkspace() {}

    RemoteWorkspace(
            UUID sessionId,
            UUID userId,
            UUID deviceId,
            String repository,
            String branch,
            String baseBranch,
            Instant now) {
        this(sessionId, userId, deviceId, SESSION, now);
        this.repository = repository;
        this.branch = branch;
        this.baseBranch = baseBranch;
    }

    private RemoteWorkspace(UUID sessionId, UUID userId, UUID deviceId, String kind, Instant now) {
        super(Ids.newId());
        this.sessionId = sessionId;
        this.userId = userId;
        this.deviceId = deviceId;
        this.kind = kind;
        this.containerName = containerName(kind, sessionId);
        this.volumeName = volumeName(kind, sessionId);
        this.status = QUEUED;
        this.lastActiveAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static RemoteWorkspace sandbox(UUID sessionId, UUID userId, UUID deviceId, Instant now) {
        return new RemoteWorkspace(sessionId, userId, deviceId, SANDBOX, now);
    }

    static String containerName(String kind, UUID sessionId) {
        return (SANDBOX.equals(kind) ? "pointer-sandbox-" : "pointer-session-") + sessionId;
    }

    static String volumeName(String kind, UUID sessionId) {
        return (SANDBOX.equals(kind) ? "pointer-sandbox-" : "pointer-workspace-") + sessionId;
    }

    void changeStatus(String status, String error, Instant now) {
        this.status = status;
        this.error = error;
        this.updatedAt = now;
    }

    void prepared(String baseBranch, Instant now) {
        this.baseBranch = baseBranch;
        changeStatus(READY, null, now);
    }

    void pushed(String commit, Instant now) {
        this.pushedCommit = commit;
        this.updatedAt = now;
    }

    void pullRequestOpened(String url, Instant now) {
        this.pullRequestUrl = url;
        this.updatedAt = now;
    }

    void desktopViewSecretIssued(byte[] secret, Instant now) {
        this.desktopViewSecret = secret;
        this.updatedAt = now;
    }

    void synced(long files, long bytes, Instant now) {
        this.syncedAt = now;
        this.syncedFiles = Math.toIntExact(files);
        this.syncedBytes = bytes;
        this.updatedAt = now;
    }

    boolean hasDesktopView() {
        return desktopViewSecret != null;
    }

    void touch(Instant now) {
        this.lastActiveAt = now;
    }

    boolean isSandbox() {
        return SANDBOX.equals(kind);
    }

    boolean isReady() {
        return READY.equals(status);
    }
}
