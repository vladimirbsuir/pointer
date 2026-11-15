package dev.pointer.automation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface AutomationRepository extends JpaRepository<Automation, UUID> {

    Optional<Automation> findByProjectId(UUID projectId);

    List<Automation> findByUserIdAndEnabledTrue(UUID userId);
}

interface AutomationRunRepository extends JpaRepository<AutomationRun, UUID> {

    List<AutomationRun> findByAutomationIdOrderByStartedAtDesc(UUID automationId, Limit limit);

    Optional<AutomationRun> findByIdAndAutomationId(UUID id, UUID automationId);

    Optional<AutomationRun> findFirstBySessionIdAndStatusIn(UUID sessionId, Collection<String> statuses);

    Optional<AutomationRun> findFirstBySessionIdOrderByStartedAtDesc(UUID sessionId);

    List<AutomationRun> findByStatusIn(Collection<String> statuses);

    boolean existsByAutomationIdAndIssueNumberAndStatusIn(
            UUID automationId, int issueNumber, Collection<String> statuses);

    long countByAutomationIdAndIssueNumber(UUID automationId, int issueNumber);
}
