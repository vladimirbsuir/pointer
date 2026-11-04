package dev.pointer.session;

import dev.pointer.common.error.ApiException;
import dev.pointer.project.ProjectService;
import dev.pointer.provider.ProviderService;
import dev.pointer.user.SettingsService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

    static final String DEFAULT_MODE = "agent";
    private static final int MAX_EVENTS_PAGE = 1000;

    private final AgentSessionRepository sessions;
    private final ItemRepository items;
    private final TurnRepository turns;
    private final ApplicationEventPublisher publisher;
    private final SessionEventLog eventLog;
    private final SessionEventBus bus;
    private final ProjectService projectService;
    private final ProviderService providerService;
    private final SettingsService settingsService;
    private final Clock clock;

    SessionService(
            AgentSessionRepository sessions,
            ItemRepository items,
            TurnRepository turns,
            ApplicationEventPublisher publisher,
            SessionEventLog eventLog,
            SessionEventBus bus,
            ProjectService projectService,
            ProviderService providerService,
            SettingsService settingsService,
            Clock clock) {
        this.sessions = sessions;
        this.items = items;
        this.turns = turns;
        this.publisher = publisher;
        this.eventLog = eventLog;
        this.bus = bus;
        this.projectService = projectService;
        this.providerService = providerService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    @Transactional
    public SessionDtos.SessionResponse create(
            UUID userId, UUID deviceId, UUID projectId, SessionDtos.CreateSessionRequest request) {
        projectService.owned(userId, projectId);
        var modelId = request.modelId() != null ? request.modelId() : settingsService.defaultModel(userId);
        if (modelId != null && !providerService.modelBelongsTo(userId, modelId)) {
            throw ApiException.notFound("model_not_found", "Model does not exist");
        }
        var mode = request.mode() == null ? DEFAULT_MODE : request.mode();
        var title = request.title() == null ? null : request.title().strip();
        var session =
                sessions.save(new AgentSession(userId, projectId, deviceId, title, mode, modelId, clock.instant()));
        notifySessionsChanged(session, "created");
        return toResponse(session, 0);
    }

    @Transactional(readOnly = true)
    public List<SessionDtos.SessionResponse> list(UUID userId, UUID projectId) {
        projectService.owned(userId, projectId);
        return sessions.findByUserIdAndProjectIdOrderByUpdatedAtDesc(userId, projectId).stream()
                .map(session -> toResponse(session, currentSeq(session.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SessionDtos.SessionResponse get(UUID userId, UUID sessionId) {
        var session = owned(userId, sessionId);
        return toResponse(session, currentSeq(sessionId));
    }

    @Transactional
    public SessionDtos.SessionResponse update(UUID userId, UUID sessionId, SessionDtos.UpdateSessionRequest request) {
        var session = owned(userId, sessionId);
        var now = clock.instant();
        if (request.title() != null) {
            session.rename(request.title().strip(), false, now);
        }
        if (request.mode() != null) {
            session.changeMode(request.mode(), now);
        }
        if (request.modelId() != null) {
            if (!providerService.modelBelongsTo(userId, request.modelId())) {
                throw ApiException.notFound("model_not_found", "Model does not exist");
            }
            session.changeModel(request.modelId(), now);
        }
        if (request.autoApprove() != null) {
            session.changeAutoApprove(request.autoApprove(), now);
        }
        if (request.archived() != null) {
            session.archive(request.archived(), now);
        }
        sessions.save(session);
        var event = eventLog.append(userId, sessionId, "session.updated", settingsPayload(session));
        notifySessionsChanged(session, "updated");
        return toResponse(session, event.seq());
    }

    @Transactional
    public void delete(UUID userId, UUID sessionId) {
        var session = owned(userId, sessionId);
        sessions.delete(session);
        notifySessionsChanged(session, "deleted");
        if (session.activeWorktree() != null) {
            publisher.publishEvent(new SessionDeleted(
                    session.getId(), session.getProjectId(), session.getExecutorDeviceId(), session.activeWorktree()));
        }
    }

    @Transactional(readOnly = true)
    public AgentSession worktreeCandidate(UUID userId, UUID sessionId) {
        var session = idle(userId, sessionId);
        if (session.getWorktreeBranch() != null) {
            throw ApiException.conflict("worktree_exists", "The session already has a worktree");
        }
        if (turns.maxNumber(sessionId) > 0) {
            throw ApiException.conflict("session_started", "A worktree can be created only before the first message");
        }
        return session;
    }

    @Transactional(readOnly = true)
    public AgentSession withActiveWorktree(UUID userId, UUID sessionId) {
        var session = idle(userId, sessionId);
        if (session.activeWorktree() == null) {
            throw ApiException.conflict("no_worktree", "The session has no active worktree");
        }
        return session;
    }

    @Transactional
    public SessionDtos.SessionResponse attachWorktree(UUID userId, UUID sessionId, String branch, String baseBranch) {
        var session = owned(userId, sessionId);
        session.attachWorktree(branch, baseBranch, clock.instant());
        return saveSettings(userId, session);
    }

    @Transactional
    public SessionDtos.SessionResponse closeWorktree(UUID userId, UUID sessionId, String state) {
        var session = owned(userId, sessionId);
        session.closeWorktree(state, clock.instant());
        return saveSettings(userId, session);
    }

    private SessionDtos.SessionResponse saveSettings(UUID userId, AgentSession session) {
        sessions.save(session);
        var event = eventLog.append(userId, session.getId(), "session.updated", settingsPayload(session));
        notifySessionsChanged(session, "updated");
        return toResponse(session, event.seq());
    }

    private AgentSession idle(UUID userId, UUID sessionId) {
        var session = owned(userId, sessionId);
        if (!SessionStatus.IDLE.equals(session.getStatus()) && !SessionStatus.ERROR.equals(session.getStatus())) {
            throw ApiException.conflict("session_busy", "Wait until the agent finishes the current turn");
        }
        return session;
    }

    public record SessionDeleted(UUID sessionId, UUID projectId, UUID deviceId, String worktree) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> items(UUID userId, UUID sessionId) {
        owned(userId, sessionId);
        return items.findVisibleBySessionId(sessionId).stream()
                .map(Item::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SessionDtos.SessionEventResponse> events(UUID userId, UUID sessionId, long afterSeq) {
        owned(userId, sessionId);
        return eventsAfter(sessionId, afterSeq);
    }

    @Transactional(readOnly = true)
    public List<SessionDtos.SessionEventResponse> eventsAfter(UUID sessionId, long afterSeq) {
        return eventLog.after(sessionId, afterSeq, MAX_EVENTS_PAGE).stream()
                .map(record -> new SessionDtos.SessionEventResponse(
                        record.sessionId(), record.seq(), record.occurredAt(), record.event()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentSession owned(UUID userId, UUID sessionId) {
        return sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("session_not_found", "Session does not exist"));
    }

    @Transactional(readOnly = true)
    public long currentSeq(UUID sessionId) {
        return eventLog.lastSeq(sessionId);
    }

    void notifySessionsChanged(AgentSession session, String change) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", session.getId());
        payload.put("projectId", session.getProjectId());
        payload.put("change", change);
        payload.put("status", session.getStatus());
        payload.put("title", session.getTitle());
        eventLog.afterCommit(() -> bus.publishSessionsChanged(session.getUserId(), payload));
    }

    static Map<String, Object> settingsPayload(AgentSession session) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", session.getTitle());
        payload.put("mode", session.getMode());
        payload.put("modelId", session.getModelId());
        payload.put("autoApprove", session.isAutoApprove());
        payload.put("archived", session.getArchivedAt() != null);
        payload.put("worktree", worktreePayload(session));
        return payload;
    }

    private static Map<String, Object> worktreePayload(AgentSession session) {
        if (session.getWorktreeBranch() == null) {
            return null;
        }
        var worktree = new LinkedHashMap<String, Object>();
        worktree.put("branch", session.getWorktreeBranch());
        worktree.put("baseBranch", session.getWorktreeBaseBranch());
        worktree.put("state", session.getWorktreeState());
        return worktree;
    }

    static SessionDtos.SessionResponse toResponse(AgentSession session, long lastSeq) {
        return new SessionDtos.SessionResponse(
                session.getId(),
                session.getProjectId(),
                session.getTitle(),
                session.getMode(),
                session.getModelId(),
                session.getStatus(),
                session.isAutoApprove(),
                lastSeq,
                session.getContextTokens(),
                session.getTotalInputTokens(),
                session.getTotalOutputTokens(),
                session.getExecutorDeviceId(),
                session.getWorktreeBranch() == null
                        ? null
                        : new SessionDtos.SessionWorktree(
                                session.getWorktreeBranch(),
                                session.getWorktreeBaseBranch(),
                                session.getWorktreeState()),
                session.getArchivedAt(),
                session.getCreatedAt(),
                session.getUpdatedAt());
    }
}
