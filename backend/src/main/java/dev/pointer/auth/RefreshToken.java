package dev.pointer.auth;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;

@Entity
@Table(name = "refresh_tokens")
@Getter(AccessLevel.PACKAGE)
class RefreshToken extends BaseEntity {

    private UUID userId;
    private UUID deviceId;
    private UUID familyId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant rotatedAt;
    private Instant revokedAt;
    private Instant createdAt;

    protected RefreshToken() {}

    RefreshToken(UUID userId, UUID deviceId, UUID familyId, String tokenHash, Instant expiresAt, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.deviceId = deviceId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    boolean isUsed() {
        return rotatedAt != null || revokedAt != null;
    }

    void markRotated(Instant now) {
        rotatedAt = now;
    }

    void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
