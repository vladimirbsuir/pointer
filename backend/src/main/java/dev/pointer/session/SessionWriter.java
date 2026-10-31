package dev.pointer.session;

import dev.pointer.common.error.ApiException;
import dev.pointer.provider.ProviderService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionWriter {

    private final AgentSessionRepository sessions;
    private final TurnRepository turns;
    private final ItemRepository items;
    private final SessionEventLog eventLog;
    private final SessionService sessionService;
    private final ProviderService providerService;
    private final EntityManager entityManager;
    private final Clock clock;

    SessionWriter(
            AgentSessionRepository sessions,
            TurnRepository turns,
            ItemRepository items,
            SessionEventLog eventLog,
            SessionService sessionService,
            ProviderService providerService,
            EntityManager entityManager,
            Clock clock) {
        this.sessions = sessions;
        this.turns = turns;
        this.items = items;
        this.eventLog = eventLog;
        this.sessionService = sessionService;
        this.providerService = providerService;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public AcceptedMessage acceptUserMessage(
            UUID userId, UUID sessionId, SessionDtos.SendMessageRequest request, String idempotencyKey) {
        var session = lockOwned(userId, sessionId);
        if (idempotencyKey != null) {
            var existing = turns.findBySessionIdAndIdempotencyKey(sessionId, idempotencyKey);
            if (existing.isPresent()) {
                return new AcceptedMessage(null, existing.get().getId(), false, false);
            }
        }
        var now = clock.instant();
        if (request.mode() != null) {
            session.changeMode(request.mode(), now);
        }
        if (request.modelId() != null) {
            if (!providerService.modelBelongsTo(userId, request.modelId())) {
                throw ApiException.notFound("model_not_found", "Model does not exist");
            }
            session.changeModel(request.modelId(), now);
        }
        if (session.getModelId() == null) {
            throw ApiException.badRequest("model_required", "Choose a model before sending a message");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("text", request.text());
        payload.put("attachments", request.attachments() == null ? List.of() : request.attachments());
        payload.put("mode", session.getMode());
        if (!SessionStatus.IDLE.equals(session.getStatus()) && !SessionStatus.ERROR.equals(session.getStatus())) {
            var queued = saveItem(session, null, Item.USER_MESSAGE, Item.COMPLETED, payload);
            sessions.save(session);
            return new AcceptedMessage(queued.getId(), null, true, false);
        }
        var turn = openTurn(session, idempotencyKey);
        var item = saveItem(session, turn.getId(), Item.USER_MESSAGE, Item.COMPLETED, payload);
        return new AcceptedMessage(item.getId(), turn.getId(), false, true);
    }

    @Transactional
    public Map<String, Object> createItem(
            UUID userId, UUID sessionId, UUID turnId, String type, Map<String, Object> payload) {
        var session = lockOwned(userId, sessionId);
        return saveItem(session, turnId, type, Item.IN_PROGRESS, payload).toView();
    }

    @Transactional
    public Map<String, Object> completeItem(
            UUID userId, UUID sessionId, UUID itemId, String status, Map<String, Object> updates) {
        var item = items.findById(itemId)
                .filter(found -> found.getSessionId().equals(sessionId))
                .orElseThrow(() -> ApiException.notFound("item_not_found", "Item does not exist"));
        item.complete(status, updates, clock.instant());
        items.save(item);
        var view = item.toView();
        eventLog.append(userId, sessionId, "item.completed", Map.of("item", view));
        return view;
    }

    @Transactional
    public void recordUsage(
            UUID userId, UUID sessionId, UUID turnId, long promptTokens, long completionTokens, Integer contextWindow) {
        var session = lockOwned(userId, sessionId);
        var turn = turns.findById(turnId).orElseThrow();
        turn.addUsage(promptTokens, completionTokens);
        turns.save(turn);
        session.recordUsage((int) (promptTokens + completionTokens), promptTokens, completionTokens, clock.instant());
        sessions.save(session);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("contextTokens", session.getContextTokens());
        payload.put("contextWindow", contextWindow);
        payload.put("totalInputTokens", session.getTotalInputTokens());
        payload.put("totalOutputTokens", session.getTotalOutputTokens());
        eventLog.append(userId, sessionId, "usage.updated", payload);
    }

    @Transactional
    public Optional<UUID> finishTurn(
            UUID userId, UUID sessionId, UUID turnId, String reason, Map<String, Object> error) {
        var session = lockOwned(userId, sessionId);
        var turn = turns.findById(turnId).orElseThrow();
        turn.finish(reason, error, clock.instant());
        turns.save(turn);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("turnId", turnId);
        payload.put("reason", reason);
        payload.put("usage", Map.of("inputTokens", turn.getInputTokens(), "outputTokens", turn.getOutputTokens()));
        payload.put("error", error);
        eventLog.append(userId, sessionId, "turn.completed", payload);
        var hasQueued =
                !items.findBySessionIdAndTurnIdIsNullOrderByPosition(sessionId).isEmpty();
        if (Turn.COMPLETED.equals(reason) && hasQueued) {
            var next = openTurn(session, null);
            items.attachQueued(sessionId, next.getId());
            return Optional.of(next.getId());
        }
        changeStatus(session, Turn.FAILED.equals(reason) ? SessionStatus.ERROR : SessionStatus.IDLE);
        return Optional.empty();
    }

    @Transactional
    public boolean applyGeneratedTitle(UUID userId, UUID sessionId, String title) {
        var session = lockOwned(userId, sessionId);
        if (!session.isTitleGenerated() || !AgentSession.DEFAULT_TITLE.equals(session.getTitle())) {
            return false;
        }
        session.rename(title, true, clock.instant());
        sessions.save(session);
        eventLog.append(userId, sessionId, "session.updated", SessionService.settingsPayload(session));
        sessionService.notifySessionsChanged(session, "updated");
        return true;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> conversation(UUID sessionId) {
        return items.findBySessionIdOrderByPosition(sessionId).stream()
                .map(Item::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public TurnContext turnContext(UUID userId, UUID sessionId, UUID turnId) {
        var session = sessionService.owned(userId, sessionId);
        var turn = turns.findById(turnId).orElseThrow();
        return new TurnContext(
                session.getId(),
                session.getUserId(),
                session.getProjectId(),
                session.getExecutorDeviceId(),
                turn.getId(),
                turn.getNumber(),
                turn.getMode(),
                turn.getModelId(),
                session.isTitleGenerated() && AgentSession.DEFAULT_TITLE.equals(session.getTitle()));
    }

    @Transactional
    public int recoverInterrupted() {
        var now = clock.instant();
        var interrupted = turns.findByStatus(Turn.RUNNING);
        interrupted.forEach(turn -> turn.finish(Turn.INTERRUPTED, null, now));
        turns.saveAll(interrupted);
        var unfinished = items.findByStatus(Item.IN_PROGRESS);
        unfinished.forEach(item -> item.complete(Item.CANCELLED, Map.of(), now));
        items.saveAll(unfinished);
        var busy = sessions.findByStatusIn(
                List.of(SessionStatus.RUNNING, SessionStatus.WAITING_FOR_APPROVAL, SessionStatus.WAITING_FOR_EXECUTOR));
        busy.forEach(session -> session.changeStatus(SessionStatus.IDLE, now));
        sessions.saveAll(busy);
        return interrupted.size();
    }

    private Turn openTurn(AgentSession session, String idempotencyKey) {
        var number = turns.maxNumber(session.getId()) + 1;
        var turn = turns.saveAndFlush(new Turn(
                session.getId(), number, session.getMode(), session.getModelId(), idempotencyKey, clock.instant()));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("turnId", turn.getId());
        payload.put("mode", turn.getMode());
        payload.put("modelId", turn.getModelId());
        eventLog.append(session.getUserId(), session.getId(), "turn.started", payload);
        changeStatus(session, SessionStatus.RUNNING);
        return turn;
    }

    private Item saveItem(AgentSession session, UUID turnId, String type, String status, Map<String, Object> payload) {
        var position = eventLog.nextPosition(session.getId());
        var item = items.save(new Item(session.getId(), turnId, position, type, status, payload, clock.instant()));
        eventLog.append(
                session.getUserId(),
                session.getId(),
                "item.created",
                turnId == null ? Map.of("item", item.toView()) : Map.of("turnId", turnId, "item", item.toView()));
        return item;
    }

    private void changeStatus(AgentSession session, String status) {
        if (status.equals(session.getStatus())) {
            sessions.save(session);
            return;
        }
        session.changeStatus(status, clock.instant());
        sessions.save(session);
        eventLog.append(session.getUserId(), session.getId(), "status.changed", Map.of("status", status));
        sessionService.notifySessionsChanged(session, "status");
    }

    private AgentSession lockOwned(UUID userId, UUID sessionId) {
        var session = sessionService.owned(userId, sessionId);
        entityManager.lock(session, LockModeType.PESSIMISTIC_WRITE);
        return session;
    }

    public record AcceptedMessage(UUID itemId, UUID turnId, boolean queued, boolean startTurn) {}

    public record TurnContext(
            UUID sessionId,
            UUID userId,
            UUID projectId,
            UUID executorDeviceId,
            UUID turnId,
            int turnNumber,
            String mode,
            UUID modelId,
            boolean needsTitle) {}
}
