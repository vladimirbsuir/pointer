package dev.pointer.session;

import dev.pointer.common.error.ApiException;
import dev.pointer.provider.llm.Cancellation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalService {

    private static final long POLL_MILLIS = 250;

    private final ApprovalRepository approvals;
    private final SessionEventLog eventLog;
    private final SessionService sessionService;
    private final SessionWriter writer;
    private final EntityManager entityManager;
    private final Clock clock;
    private final Map<UUID, CompletableFuture<Decision>> waiting = new ConcurrentHashMap<>();

    ApprovalService(
            ApprovalRepository approvals,
            SessionEventLog eventLog,
            SessionService sessionService,
            SessionWriter writer,
            EntityManager entityManager,
            Clock clock) {
        this.approvals = approvals;
        this.eventLog = eventLog;
        this.sessionService = sessionService;
        this.writer = writer;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public UUID request(UUID userId, UUID sessionId, UUID itemId, Map<String, Object> preview) {
        var approval = approvals.save(new Approval(sessionId, itemId, preview, clock.instant()));
        waiting.put(approval.getId(), new CompletableFuture<>());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("approvalId", approval.getId());
        payload.put("itemId", itemId);
        payload.put("preview", preview);
        eventLog.append(userId, sessionId, "approval.requested", payload);
        writer.changeStatus(userId, sessionId, SessionStatus.WAITING_FOR_APPROVAL);
        return approval.getId();
    }

    public Decision await(UUID userId, UUID sessionId, UUID approvalId, Cancellation cancellation) {
        var future = waiting.get(approvalId);
        try {
            while (true) {
                try {
                    return future.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException exception) {
                    if (cancellation.isCancelled()) {
                        cancel(userId, sessionId, approvalId);
                        return new Decision(Approval.CANCELLED, null, null);
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Decision(Approval.CANCELLED, null, null);
        } catch (ExecutionException exception) {
            return new Decision(Approval.CANCELLED, null, null);
        } finally {
            waiting.remove(approvalId);
        }
    }

    @Transactional
    public void resolve(UUID userId, UUID sessionId, UUID approvalId, ResolveApprovalRequest request) {
        sessionService.owned(userId, sessionId);
        var approval = approvals
                .findById(approvalId)
                .filter(found -> found.getSessionId().equals(sessionId))
                .orElseThrow(() -> ApiException.notFound("approval_not_found", "Approval does not exist"));
        entityManager.refresh(approval, LockModeType.PESSIMISTIC_WRITE);
        if (!approval.isPending()) {
            throw ApiException.conflict("approval_resolved", "Approval was already resolved");
        }
        var status = "approved".equals(request.decision()) ? Approval.APPROVED : Approval.DENIED;
        var scope = request.scope() == null ? "once" : request.scope();
        approval.resolve(status, scope, request.message(), clock.instant());
        approvals.save(approval);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("approvalId", approvalId);
        payload.put("decision", status);
        payload.put("scope", scope);
        payload.put("message", request.message());
        eventLog.append(userId, sessionId, "approval.resolved", payload);
        writer.changeStatus(userId, sessionId, SessionStatus.RUNNING);
        var decision = new Decision(status, scope, request.message());
        eventLog.afterCommit(() -> {
            var future = waiting.get(approvalId);
            if (future != null) {
                future.complete(decision);
            }
        });
    }

    @Transactional
    void cancel(UUID userId, UUID sessionId, UUID approvalId) {
        approvals.findById(approvalId).filter(Approval::isPending).ifPresent(approval -> {
            approval.resolve(Approval.CANCELLED, null, null, clock.instant());
            approvals.save(approval);
            eventLog.append(
                    userId,
                    sessionId,
                    "approval.resolved",
                    Map.of("approvalId", approvalId, "decision", Approval.CANCELLED));
        });
    }

    public record Decision(String status, String scope, String message) {

        public boolean approved() {
            return Approval.APPROVED.equals(status);
        }

        public boolean forSession() {
            return approved() && "session".equals(scope);
        }

        public boolean cancelled() {
            return Approval.CANCELLED.equals(status);
        }
    }

    public record ResolveApprovalRequest(
            @Pattern(regexp = "approved|denied") String decision,
            @Pattern(regexp = "once|session") String scope,
            @Size(max = 2000) String message) {}
}

interface ApprovalRepository extends JpaRepository<Approval, UUID> {}
