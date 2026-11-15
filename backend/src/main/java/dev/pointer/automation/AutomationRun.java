package dev.pointer.automation;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "automation_runs")
@Getter
public class AutomationRun extends BaseEntity {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String CHECKING = "checking";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    public static final String TRIGGER_LABEL = "label";
    public static final String TRIGGER_DESKTOP = "desktop";
    public static final String TRIGGER_TELEGRAM = "telegram";

    static final String NEEDS_INPUT = "needs_input";
    static final String CHECK_FAILED = "check_failed";
    static final String BUDGET_EXCEEDED = "budget_exceeded";
    static final String AGENT_FAILED = "agent_failed";
    static final String START_FAILED = "start_failed";
    static final String WORKSPACE_FAILED = "workspace_failed";
    static final String PULL_REQUEST_FAILED = "pull_request_failed";
    static final String ERROR = "error";

    static final List<String> ACTIVE = List.of(QUEUED, RUNNING, CHECKING);

    private UUID automationId;
    private UUID sessionId;
    private int issueNumber;
    private String issueTitle;
    private String trigger;
    private String status;
    private String failureReason;
    private String error;
    private int attempts;
    private String lastCheckOutput;
    private String pullRequestUrl;
    private Long statusCommentId;
    private BigDecimal costUsd;
    private Instant startedAt;
    private Instant finishedAt;

    protected AutomationRun() {}

    AutomationRun(UUID automationId, int issueNumber, String issueTitle, String trigger, Instant now) {
        super(Ids.newId());
        this.automationId = automationId;
        this.issueNumber = issueNumber;
        this.issueTitle = issueTitle;
        this.trigger = trigger;
        this.status = QUEUED;
        this.costUsd = BigDecimal.ZERO;
        this.startedAt = now;
    }

    boolean isActive() {
        return ACTIVE.contains(status);
    }

    void attachSession(UUID sessionId) {
        this.sessionId = sessionId;
    }

    void detachSession() {
        this.sessionId = null;
    }

    void retitle(String issueTitle) {
        this.issueTitle = issueTitle;
    }

    void statusCommentPosted(long commentId) {
        this.statusCommentId = commentId;
    }

    void changeStatus(String status) {
        this.status = status;
    }

    void checkFinished(String output) {
        this.attempts++;
        this.lastCheckOutput = output;
    }

    void recordCost(BigDecimal costUsd) {
        if (costUsd != null) {
            this.costUsd = costUsd;
        }
    }

    void pullRequestOpened(String url) {
        this.pullRequestUrl = url;
    }

    void succeed(Instant now) {
        this.status = SUCCEEDED;
        this.finishedAt = now;
    }

    void fail(String reason, String error, Instant now) {
        this.status = FAILED;
        this.failureReason = reason;
        this.error = error;
        this.finishedAt = now;
    }

    void cancel(Instant now) {
        this.status = CANCELLED;
        this.finishedAt = now;
    }
}
