package dev.pointer.session;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "approvals")
@Getter(AccessLevel.PACKAGE)
class Approval extends BaseEntity {

    static final String PENDING = "pending";
    static final String APPROVED = "approved";
    static final String DENIED = "denied";
    static final String CANCELLED = "cancelled";

    private UUID sessionId;
    private UUID itemId;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> preview;

    private String status;
    private String scope;
    private String message;
    private Instant createdAt;
    private Instant resolvedAt;

    protected Approval() {}

    Approval(UUID sessionId, UUID itemId, Map<String, Object> preview, Instant now) {
        super(Ids.newId());
        this.sessionId = sessionId;
        this.itemId = itemId;
        this.preview = new HashMap<>(preview);
        this.status = PENDING;
        this.createdAt = now;
    }

    boolean isPending() {
        return PENDING.equals(status);
    }

    void resolve(String status, String scope, String message, Instant now) {
        this.status = status;
        this.scope = scope;
        this.message = message;
        this.resolvedAt = now;
    }
}
