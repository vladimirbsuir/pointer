package dev.pointer.automation;

import dev.pointer.session.SessionEventPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class AutomationRuns {

    static final String EVENT = "automation.updated";

    private final AutomationRunRepository runs;
    private final AutomationRepository automations;
    private final SessionEventPublisher events;
    private final EntityManager entityManager;

    @Transactional
    Optional<AutomationRun> transition(UUID runId, Consumer<AutomationRun> change) {
        var changed = locked(runId).filter(AutomationRun::isActive);
        changed.ifPresent(run -> {
            change.accept(run);
            runs.save(run);
            publish(run);
        });
        return changed;
    }

    @Transactional
    void record(UUID runId, Consumer<AutomationRun> change) {
        locked(runId).ifPresent(run -> {
            change.accept(run);
            runs.save(run);
        });
    }

    @Transactional(readOnly = true)
    Optional<AutomationRun> active(UUID runId) {
        return runs.findById(runId).filter(AutomationRun::isActive);
    }

    private Optional<AutomationRun> locked(UUID runId) {
        return Optional.ofNullable(entityManager.find(AutomationRun.class, runId, LockModeType.PESSIMISTIC_WRITE));
    }

    private void publish(AutomationRun run) {
        if (run.getSessionId() == null) {
            return;
        }
        var automation = automations.findById(run.getAutomationId()).orElseThrow();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("runId", run.getId());
        payload.put("status", run.getStatus());
        payload.put("attempts", run.getAttempts());
        payload.put("maxAttempts", automation.getMaxAttempts());
        payload.put("issueNumber", run.getIssueNumber());
        payload.put("issueTitle", run.getIssueTitle());
        payload.put("failureReason", run.getFailureReason());
        payload.put("pullRequestUrl", run.getPullRequestUrl());
        events.append(automation.getUserId(), run.getSessionId(), EVENT, payload);
    }
}
