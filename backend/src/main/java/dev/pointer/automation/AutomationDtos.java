package dev.pointer.automation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AutomationDtos {

    public static final String WEBHOOK_CREATED = "created";
    public static final String WEBHOOK_MISSING = "missing";
    public static final String WEBHOOK_NO_PUBLIC_URL = "no_public_url";

    private AutomationDtos() {}

    public record AutomationResponse(
            boolean configured,
            boolean enabled,
            String triggerLabel,
            String baseBranch,
            UUID modelId,
            String checkCommand,
            int maxAttempts,
            BigDecimal budgetUsd,
            boolean escalation,
            String webhook,
            String webhookError,
            Instant updatedAt) {

        static AutomationResponse notConfigured(String webhook) {
            return new AutomationResponse(
                    false,
                    false,
                    Automation.DEFAULT_TRIGGER_LABEL,
                    null,
                    null,
                    null,
                    Automation.DEFAULT_MAX_ATTEMPTS,
                    null,
                    false,
                    webhook,
                    null,
                    null);
        }

        static AutomationResponse of(Automation automation, String webhook, String webhookError) {
            return new AutomationResponse(
                    true,
                    automation.isEnabled(),
                    automation.getTriggerLabel(),
                    automation.getBaseBranch(),
                    automation.getModelId(),
                    automation.getCheckCommand(),
                    automation.getMaxAttempts(),
                    automation.getBudgetUsd(),
                    automation.isEscalation(),
                    webhook,
                    webhookError,
                    automation.getUpdatedAt());
        }
    }

    public record SaveAutomationRequest(
            @NotNull Boolean enabled,

            @Size(min = 1, max = 50) @Pattern(regexp = "^[^,\\r\\n]+$") String triggerLabel,

            @Size(max = 200) @Pattern(regexp = "^[A-Za-z0-9._/-]*$") String baseBranch,

            UUID modelId,

            @Size(max = 2000) String checkCommand,

            @Min(1) @Max(10) Integer maxAttempts,

            @DecimalMin("0") @DecimalMax("100000") BigDecimal budgetUsd,

            Boolean escalation) {}

    public record StartRunRequest(@NotNull @Min(1) Integer issueNumber) {}

    public record RunResponse(
            UUID id,
            UUID sessionId,
            int issueNumber,
            String issueTitle,
            String issueUrl,
            String trigger,
            String status,
            String failureReason,
            String error,
            int attempts,
            int maxAttempts,
            String lastCheckOutput,
            String pullRequestUrl,
            BigDecimal costUsd,
            Instant startedAt,
            Instant finishedAt) {

        static RunResponse of(AutomationRun run, Automation automation, String repository) {
            return new RunResponse(
                    run.getId(),
                    run.getSessionId(),
                    run.getIssueNumber(),
                    run.getIssueTitle(),
                    repository == null ? null : "https://github.com/" + repository + "/issues/" + run.getIssueNumber(),
                    run.getTrigger(),
                    run.getStatus(),
                    run.getFailureReason(),
                    run.getError(),
                    run.getAttempts(),
                    automation.getMaxAttempts(),
                    run.getLastCheckOutput(),
                    run.getPullRequestUrl(),
                    run.getCostUsd(),
                    run.getStartedAt(),
                    run.getFinishedAt());
        }
    }
}
