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

    private UUID sessionId;
    private UUID userId;
    private UUID deviceId;
    private String repository;
    private String branch;
    private String baseBranch;
    private String containerName;
    private String volumeName;
    private String status;
    private String error;
    private String pushedCommit;
    private String pullRequestUrl;
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
        super(Ids.newId());
        var suffix = sessionId.toString();
        this.sessionId = sessionId;
        this.userId = userId;
        this.deviceId = deviceId;
        this.repository = repository;
        this.branch = branch;
        this.baseBranch = baseBranch;
        this.containerName = "pointer-session-" + suffix;
        this.volumeName = "pointer-workspace-" + suffix;
        this.status = QUEUED;
        this.lastActiveAt = now;
        this.createdAt = now;
        this.updatedAt = now;
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

    void touch(Instant now) {
        this.lastActiveAt = now;
    }

    boolean isReady() {
        return READY.equals(status);
    }
}
