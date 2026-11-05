package dev.pointer.device;

import dev.pointer.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "devices")
@Getter
public class Device extends BaseEntity {

    public static final String DESKTOP = "desktop";
    public static final String RUNNER = "runner";

    private UUID userId;
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> platform;

    private String clientVersion;
    private String kind;
    private String runnerSecretHash;
    private Instant lastSeenAt;
    private Instant revokedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected Device() {}

    Device(UUID id, UUID userId, DeviceInfo info, Instant now) {
        super(id);
        this.userId = userId;
        this.createdAt = now;
        this.kind = DESKTOP;
        update(userId, info, now);
    }

    static Device runner(UUID id, UUID userId, String name, String secretHash, Instant now) {
        var device = new Device(id, userId, new DeviceInfo(id, name, Map.of("os", "linux"), null), now);
        device.kind = RUNNER;
        device.runnerSecretHash = secretHash;
        return device;
    }

    void rotateRunnerSecret(String secretHash, Instant now) {
        this.runnerSecretHash = secretHash;
        this.revokedAt = null;
        this.updatedAt = now;
    }

    void revoke(Instant now) {
        this.revokedAt = now;
        this.runnerSecretHash = null;
        this.updatedAt = now;
    }

    void touch(Instant now) {
        this.lastSeenAt = now;
    }

    public boolean isRunner() {
        return RUNNER.equals(kind);
    }

    void update(UUID userId, DeviceInfo info, Instant now) {
        this.userId = userId;
        this.name = info.name();
        this.platform = info.platform() == null ? new HashMap<>() : new HashMap<>(info.platform());
        this.clientVersion = info.clientVersion();
        this.lastSeenAt = now;
        this.revokedAt = null;
        this.updatedAt = now;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
