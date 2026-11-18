package dev.pointer.automation;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "automations")
@Getter
public class Automation extends BaseEntity {

    static final String DEFAULT_TRIGGER_LABEL = "pointer";
    static final int DEFAULT_MAX_ATTEMPTS = 3;

    private UUID userId;
    private UUID projectId;
    private boolean enabled;
    private String triggerLabel;
    private String baseBranch;
    private UUID modelId;
    private String checkCommand;
    private int maxAttempts;
    private BigDecimal budgetUsd;
    private boolean escalation;
    private Long webhookId;
    private byte[] webhookSecretEncrypted;
    private Instant createdAt;
    private Instant updatedAt;

    protected Automation() {}

    Automation(UUID userId, UUID projectId, byte[] webhookSecretEncrypted, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.projectId = projectId;
        this.enabled = true;
        this.triggerLabel = DEFAULT_TRIGGER_LABEL;
        this.maxAttempts = DEFAULT_MAX_ATTEMPTS;
        this.webhookSecretEncrypted = webhookSecretEncrypted;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void configure(Settings settings, Instant now) {
        this.enabled = settings.enabled();
        this.triggerLabel = settings.triggerLabel();
        this.baseBranch = settings.baseBranch();
        this.modelId = settings.modelId();
        this.checkCommand = settings.checkCommand();
        this.maxAttempts = settings.maxAttempts();
        this.budgetUsd = settings.budgetUsd();
        this.escalation = settings.escalation();
        this.updatedAt = now;
    }

    void webhookCreated(long webhookId, Instant now) {
        this.webhookId = webhookId;
        this.updatedAt = now;
    }

    void webhookRemoved(Instant now) {
        this.webhookId = null;
        this.updatedAt = now;
    }

    boolean hasCheck() {
        return checkCommand != null && !checkCommand.isBlank();
    }

    record Settings(
            boolean enabled,
            String triggerLabel,
            String baseBranch,
            UUID modelId,
            String checkCommand,
            int maxAttempts,
            BigDecimal budgetUsd,
            boolean escalation) {}
}
