package dev.pointer.session;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "turns")
@Getter
public class Turn extends BaseEntity {

    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED = "interrupted";
    public static final String STEP_LIMIT = "step_limit";

    public static final String MESSAGE = "message";
    public static final String COMPACTION = "compaction";

    private UUID sessionId;
    private int number;
    private String kind;
    private String mode;
    private UUID modelId;
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> error;

    private long inputTokens;
    private long outputTokens;
    private String idempotencyKey;
    private Instant startedAt;
    private Instant completedAt;
    private Instant rewoundAt;

    protected Turn() {}

    Turn(UUID sessionId, int number, String kind, String mode, UUID modelId, String idempotencyKey, Instant now) {
        super(Ids.newId());
        this.sessionId = sessionId;
        this.number = number;
        this.kind = kind;
        this.mode = mode;
        this.modelId = modelId;
        this.status = RUNNING;
        this.idempotencyKey = idempotencyKey;
        this.startedAt = now;
    }

    void markRewound(Instant now) {
        this.rewoundAt = now;
    }

    void addUsage(long input, long output) {
        inputTokens += input;
        outputTokens += output;
    }

    void finish(String status, Map<String, Object> error, Instant now) {
        this.status = status;
        this.error = error;
        this.completedAt = now;
    }
}
