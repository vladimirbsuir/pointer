package dev.pointer.review;

import dev.pointer.common.error.ApiException;
import dev.pointer.execution.ExecutionListener;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionResult;
import dev.pointer.execution.ExecutionService;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.session.AgentSession;
import dev.pointer.session.SessionEventPublisher;
import dev.pointer.session.SessionService;
import dev.pointer.session.SessionStatus;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FileChangeService {

    private static final int MAX_STORED_CHARS = 1_000_000;
    private static final String RESTORE_OPERATION = "pointer.restore_file";
    private static final Duration RESTORE_TIMEOUT = Duration.ofSeconds(15);

    private final FileChangeRepository changes;
    private final SessionEventPublisher events;
    private final SessionService sessions;
    private final ExecutionService execution;
    private final TransactionTemplate transactions;
    private final Clock clock;

    FileChangeService(
            FileChangeRepository changes,
            SessionEventPublisher events,
            SessionService sessions,
            ExecutionService execution,
            TransactionTemplate transactions,
            Clock clock) {
        this.changes = changes;
        this.events = events;
        this.sessions = sessions;
        this.execution = execution;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ChangedFile> list(UUID userId, UUID sessionId) {
        sessions.owned(userId, sessionId);
        var byPath = changes.findBySessionIdOrderByCreatedAt(sessionId).stream()
                .collect(Collectors.groupingBy(FileChange::getPath, LinkedHashMap::new, Collectors.toList()));
        return byPath.entrySet().stream()
                .map(entry -> summarize(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ChangedFile::lastChangedAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public FileDiff diff(UUID userId, UUID sessionId, String path) {
        sessions.owned(userId, sessionId);
        var history = history(sessionId, path);
        var active = activeChanges(history);
        var relevant = active.isEmpty() ? history : active;
        var first = relevant.getFirst();
        var last = relevant.getLast();
        return new FileDiff(
                path,
                netKind(first, last),
                first.getBeforeContent(),
                last.getAfterContent(),
                isUnavailable(first, last),
                active.isEmpty());
    }

    public ChangedFile revert(UUID userId, UUID sessionId, String path, boolean force) {
        var session = sessions.owned(userId, sessionId);
        if (!SessionStatus.IDLE.equals(session.getStatus()) && !SessionStatus.ERROR.equals(session.getStatus())) {
            throw ApiException.conflict("session_busy", "Wait until the agent finishes the current turn");
        }
        var active = activeChanges(history(sessionId, path));
        if (active.isEmpty()) {
            throw ApiException.conflict("nothing_to_revert", "All changes to this file are already reverted");
        }
        var first = active.getFirst();
        var last = active.getLast();
        if (isUnavailable(first, last)) {
            throw ApiException.conflict("revert_unavailable", "The file is too large to restore");
        }
        if (!execution.canExecute(session.getExecutorDeviceId(), session.getProjectId())) {
            throw ApiException.conflict("executor_offline", "Open the project on the computer that ran the agent");
        }
        var result = restore(session, path, active, force, false);
        if (!result.succeeded()) {
            var error = result.error() == null ? Map.<String, Object>of() : result.error();
            var code = String.valueOf(error.getOrDefault("code", "revert_failed"));
            var message = String.valueOf(error.getOrDefault("message", "The file could not be restored"));
            throw "file_modified".equals(code)
                    ? ApiException.conflict(code, message)
                    : ApiException.badGateway(code, message);
        }
        return transactions.execute(status -> markReverted(userId, sessionId, path, active));
    }

    @Transactional
    public void record(UUID userId, UUID sessionId, UUID turnId, UUID itemId, List<Map<String, Object>> fileChanges) {
        for (var change : fileChanges) {
            var saved = changes.save(new FileChange(
                    sessionId,
                    turnId,
                    itemId,
                    String.valueOf(change.get("path")),
                    String.valueOf(change.getOrDefault("changeKind", "modified")),
                    limited(change.get("before")),
                    limited(change.get("after")),
                    number(change.get("linesAdded")),
                    number(change.get("linesRemoved")),
                    clock.instant()));
            var payload = new LinkedHashMap<String, Object>();
            payload.put("changeId", saved.getId());
            payload.put("itemId", itemId);
            payload.put("path", saved.getPath());
            payload.put("changeKind", saved.getChangeKind());
            payload.put("linesAdded", saved.getLinesAdded());
            payload.put("linesRemoved", saved.getLinesRemoved());
            events.append(userId, sessionId, "file.changed", payload);
        }
    }

    public List<String> restoreTurns(UUID userId, UUID sessionId, Collection<UUID> turnIds, boolean force) {
        var session = sessions.owned(userId, sessionId);
        var byPath = changes.findBySessionIdOrderByCreatedAt(sessionId).stream()
                .filter(change -> !change.isReverted() && turnIds.contains(change.getTurnId()))
                .collect(Collectors.groupingBy(FileChange::getPath, LinkedHashMap::new, Collectors.toList()));
        if (byPath.isEmpty()) {
            return List.of();
        }
        if (byPath.values().stream().anyMatch(group -> isUnavailable(group.getFirst(), group.getLast()))) {
            throw ApiException.conflict("revert_unavailable", "A changed file is too large to restore");
        }
        if (!execution.canExecute(session.getExecutorDeviceId(), session.getProjectId())) {
            throw ApiException.conflict("executor_offline", "Open the project on the computer that ran the agent");
        }
        if (!force) {
            var conflicts = byPath.entrySet().stream()
                    .filter(entry -> !restore(session, entry.getKey(), entry.getValue(), false, true)
                            .succeeded())
                    .map(Map.Entry::getKey)
                    .toList();
            if (!conflicts.isEmpty()) {
                throw ApiException.conflict(
                        "file_modified", "Files changed after the agent edited them: " + String.join(", ", conflicts));
            }
        }
        for (var entry : byPath.entrySet()) {
            var result = restore(session, entry.getKey(), entry.getValue(), force, false);
            if (!result.succeeded()) {
                var error = result.error() == null ? Map.<String, Object>of() : result.error();
                throw ApiException.badGateway(
                        String.valueOf(error.getOrDefault("code", "revert_failed")),
                        String.valueOf(error.getOrDefault("message", "A file could not be restored")));
            }
            transactions.executeWithoutResult(
                    status -> markReverted(userId, sessionId, entry.getKey(), entry.getValue()));
        }
        return List.copyOf(byPath.keySet());
    }

    private ExecutionResult restore(
            AgentSession session, String path, List<FileChange> group, boolean force, boolean dryRun) {
        var first = group.getFirst();
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", path);
        arguments.put("content", "created".equals(first.getChangeKind()) ? null : first.getBeforeContent());
        arguments.put("expectedContent", group.getLast().getAfterContent());
        arguments.put("force", force);
        return execution.execute(
                new ExecutionRequest(
                        session.getId(),
                        session.getProjectId(),
                        session.getExecutorDeviceId(),
                        RESTORE_OPERATION,
                        arguments,
                        dryRun,
                        RESTORE_TIMEOUT),
                new ExecutionListener() {},
                new Cancellation());
    }

    private ChangedFile markReverted(UUID userId, UUID sessionId, String path, List<FileChange> reverted) {
        var now = clock.instant();
        var ids = new ArrayList<UUID>();
        for (var change : reverted) {
            var managed = changes.findById(change.getId()).orElseThrow();
            managed.markReverted(now);
            changes.save(managed);
            ids.add(managed.getId());
        }
        events.append(userId, sessionId, "file.reverted", Map.of("changeIds", ids, "path", path));
        return summarize(path, history(sessionId, path));
    }

    private List<FileChange> history(UUID sessionId, String path) {
        var history = changes.findBySessionIdAndPathOrderByCreatedAt(sessionId, path);
        if (history.isEmpty()) {
            throw ApiException.notFound("file_change_not_found", "The agent did not change this file in the session");
        }
        return history;
    }

    private static List<FileChange> activeChanges(List<FileChange> history) {
        return history.stream().filter(change -> !change.isReverted()).toList();
    }

    private static ChangedFile summarize(String path, List<FileChange> history) {
        var active = activeChanges(history);
        var relevant = active.isEmpty() ? history : active;
        var first = relevant.getFirst();
        var last = relevant.getLast();
        return new ChangedFile(
                path,
                netKind(first, last),
                relevant.stream().mapToInt(FileChange::getLinesAdded).sum(),
                relevant.stream().mapToInt(FileChange::getLinesRemoved).sum(),
                relevant.size(),
                active.isEmpty(),
                history.getLast().getCreatedAt());
    }

    private static String netKind(FileChange first, FileChange last) {
        if ("created".equals(first.getChangeKind())) {
            return "created";
        }
        return "deleted".equals(last.getChangeKind()) ? "deleted" : "modified";
    }

    private static boolean isUnavailable(FileChange first, FileChange last) {
        var beforeMissing = !"created".equals(first.getChangeKind()) && first.getBeforeContent() == null;
        var afterMissing = !"deleted".equals(last.getChangeKind()) && last.getAfterContent() == null;
        return beforeMissing || afterMissing;
    }

    public record ChangedFile(
            String path,
            String changeKind,
            int linesAdded,
            int linesRemoved,
            int changeCount,
            boolean reverted,
            Instant lastChangedAt) {}

    public record FileDiff(
            String path, String changeKind, String before, String after, boolean unavailable, boolean reverted) {}

    public record RevertRequest(@NotBlank String path, Boolean force) {}

    private static String limited(Object content) {
        if (content == null) {
            return null;
        }
        var text = String.valueOf(content);
        return text.length() > MAX_STORED_CHARS ? null : text;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

interface FileChangeRepository extends JpaRepository<FileChange, UUID> {

    List<FileChange> findBySessionIdOrderByCreatedAt(UUID sessionId);

    List<FileChange> findBySessionIdAndPathOrderByCreatedAt(UUID sessionId, String path);
}
