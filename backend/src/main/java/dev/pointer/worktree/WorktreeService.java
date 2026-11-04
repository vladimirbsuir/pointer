package dev.pointer.worktree;

import dev.pointer.common.error.ApiException;
import dev.pointer.execution.ExecutionListener;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionService;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.session.AgentSession;
import dev.pointer.session.SessionDtos;
import dev.pointer.session.SessionService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class WorktreeService {

    static final String BRANCH_PREFIX = "pointer/";
    private static final Logger log = LoggerFactory.getLogger(WorktreeService.class);
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(2);
    private static final Set<String> CONFLICT_CODES = Set.of(
            "not_a_git_repository",
            "not_repository_root",
            "detached_head",
            "worktree_exists",
            "worktree_missing",
            "base_not_checked_out",
            "merge_conflict",
            "commit_failed");

    private final SessionService sessions;
    private final ExecutionService execution;

    WorktreeService(SessionService sessions, ExecutionService execution) {
        this.sessions = sessions;
        this.execution = execution;
    }

    public SessionDtos.SessionResponse create(UUID userId, UUID sessionId) {
        var session = sessions.worktreeCandidate(userId, sessionId);
        var branch = BRANCH_PREFIX + session.getId().toString().substring(0, 8);
        var result = run(session, branch, "pointer.worktree_create", Map.of());
        return sessions.attachWorktree(userId, sessionId, branch, String.valueOf(result.get("baseBranch")));
    }

    public Map<String, Object> status(UUID userId, UUID sessionId) {
        var session = sessions.owned(userId, sessionId);
        if (session.activeWorktree() == null) {
            throw ApiException.conflict("no_worktree", "The session has no active worktree");
        }
        return run(session, session.activeWorktree(), "pointer.worktree_status", baseBranch(session));
    }

    public SessionDtos.SessionResponse merge(UUID userId, UUID sessionId, String message) {
        var session = sessions.withActiveWorktree(userId, sessionId);
        var arguments = new LinkedHashMap<String, Object>(baseBranch(session));
        arguments.put("message", message == null || message.isBlank() ? session.getTitle() : message.strip());
        run(session, session.activeWorktree(), "pointer.worktree_merge", arguments);
        return sessions.closeWorktree(userId, sessionId, "merged");
    }

    public SessionDtos.SessionResponse discard(UUID userId, UUID sessionId) {
        var session = sessions.withActiveWorktree(userId, sessionId);
        run(session, session.activeWorktree(), "pointer.worktree_discard", Map.of());
        return sessions.closeWorktree(userId, sessionId, "discarded");
    }

    @TransactionalEventListener
    void onSessionDeleted(SessionService.SessionDeleted deleted) {
        if (!execution.canExecute(deleted.deviceId(), deleted.projectId())) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            var result = execution.execute(
                    new ExecutionRequest(
                            deleted.sessionId(),
                            deleted.projectId(),
                            deleted.deviceId(),
                            "pointer.worktree_discard",
                            Map.of(),
                            false,
                            GIT_TIMEOUT,
                            deleted.worktree()),
                    new ExecutionListener() {},
                    new Cancellation());
            if (!result.succeeded()) {
                log.warn("Worktree of deleted session {} was not removed: {}", deleted.sessionId(), result.error());
            }
        });
    }

    private Map<String, Object> run(
            AgentSession session, String branch, String operation, Map<String, Object> arguments) {
        if (!execution.canExecute(session.getExecutorDeviceId(), session.getProjectId())) {
            throw ApiException.conflict("executor_offline", "Open the project on the computer that runs the session");
        }
        var result = execution.execute(
                new ExecutionRequest(
                        session.getId(),
                        session.getProjectId(),
                        session.getExecutorDeviceId(),
                        operation,
                        arguments,
                        false,
                        GIT_TIMEOUT,
                        branch),
                new ExecutionListener() {},
                new Cancellation());
        if (!result.succeeded()) {
            var error = result.error() == null ? Map.<String, Object>of() : result.error();
            var code = String.valueOf(error.getOrDefault("code", "git_failed"));
            var message = String.valueOf(error.getOrDefault("message", "The git operation failed"));
            throw CONFLICT_CODES.contains(code)
                    ? ApiException.conflict(code, message)
                    : ApiException.badGateway(code, message);
        }
        return result.result();
    }

    private static Map<String, Object> baseBranch(AgentSession session) {
        return Map.of("baseBranch", session.getWorktreeBaseBranch());
    }
}
