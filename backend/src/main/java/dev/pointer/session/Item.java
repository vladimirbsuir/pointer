package dev.pointer.session;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "items")
public class Item extends BaseEntity {

    public static final String USER_MESSAGE = "user_message";
    public static final String ASSISTANT_MESSAGE = "assistant_message";
    public static final String REASONING = "reasoning";
    public static final String COMPACTION_SUMMARY = "compaction_summary";

    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    private UUID sessionId;
    private UUID turnId;
    private long position;
    private String type;
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    private Instant createdAt;
    private Instant completedAt;

    protected Item() {}

    Item(
            UUID sessionId,
            UUID turnId,
            long position,
            String type,
            String status,
            Map<String, Object> payload,
            Instant now) {
        super(Ids.newId());
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.position = position;
        this.type = type;
        this.status = status;
        this.payload = new HashMap<>(payload);
        this.createdAt = now;
        this.completedAt = COMPLETED.equals(status) ? now : null;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getTurnId() {
        return turnId;
    }

    public long getPosition() {
        return position;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getPayload() {
        return Map.copyOf(payload);
    }

    void attachToTurn(UUID turnId) {
        this.turnId = turnId;
    }

    void complete(String status, Map<String, Object> payloadUpdates, Instant now) {
        this.status = status;
        this.payload = new HashMap<>(payload);
        this.payload.putAll(payloadUpdates);
        this.completedAt = now;
    }

    public Map<String, Object> toView() {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", getId());
        view.put("type", type);
        view.put("turnId", turnId);
        view.put("status", status);
        view.put("createdAt", createdAt);
        view.put("completedAt", completedAt);
        view.putAll(payload);
        return view;
    }
}
