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

    private UUID userId;
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> platform;

    private String clientVersion;
    private Instant lastSeenAt;
    private Instant revokedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected Device() {}

    Device(UUID id, UUID userId, DeviceInfo info, Instant now) {
        super(id);
        this.userId = userId;
        this.createdAt = now;
        update(userId, info, now);
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
