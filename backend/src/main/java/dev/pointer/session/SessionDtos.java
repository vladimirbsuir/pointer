package dev.pointer.session;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SessionDtos {

    static final String MODE_PATTERN = "ask|plan|agent";

    private SessionDtos() {}

    public record SessionResponse(
            UUID id,
            UUID projectId,
            String title,
            String mode,
            UUID modelId,
            String status,
            boolean autoApprove,
            long lastSeq,
            int contextTokens,
            long totalInputTokens,
            long totalOutputTokens,
            BigDecimal estimatedCost,
            BigDecimal budgetUsd,
            UUID executorDeviceId,
            SessionWorktree worktree,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record SessionWorktree(String branch, String baseBranch, String state) {}

    public record CreateSessionRequest(
            @Size(min = 1, max = 200) String title,
            @Pattern(regexp = MODE_PATTERN) String mode,
            UUID modelId,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal budgetUsd) {

        public CreateSessionRequest(String title, String mode, UUID modelId) {
            this(title, mode, modelId, null);
        }
    }

    public record UsageResponse(BigDecimal estimatedCost, BigDecimal budgetUsd, List<UsageEntry> entries) {}

    public record UsageEntry(
            UUID modelId,
            String modelLabel,
            String role,
            String purpose,
            long calls,
            long promptTokens,
            long completionTokens,
            BigDecimal costUsd) {}

    public record UpdateSessionRequest(
            @Size(min = 1, max = 200) String title,
            @Pattern(regexp = MODE_PATTERN) String mode,
            UUID modelId,
            Boolean autoApprove,
            Boolean archived,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal budgetUsd) {

        public UpdateSessionRequest(String title, String mode, UUID modelId, Boolean autoApprove, Boolean archived) {
            this(title, mode, modelId, autoApprove, archived, null);
        }
    }

    public record SendMessageRequest(
            @Size(min = 1, max = 200_000) String text,
            @Size(max = 20) List<Attachment> attachments,
            @Pattern(regexp = MODE_PATTERN) String mode,
            UUID modelId) {}

    public record Attachment(
            @Pattern(regexp = "file|image") String kind,
            @Size(max = 1000) String path,
            @Size(max = 500_000) String content,
            UUID attachmentId) {}

    public record SendMessageResponse(UUID itemId, UUID turnId, boolean queued) {}

    public record SessionEventResponse(UUID sessionId, long seq, Instant occurredAt, Map<String, Object> event) {}
}
