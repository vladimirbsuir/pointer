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

    public static final String PLAN_PENDING = "pending";
    public static final String PLAN_APPROVED = "approved";
    public static final String PLAN_REJECTED = "rejected";
    static final String APPROVED_PLAN_MESSAGE = "The plan is approved. Implement it now, step by step.";

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
        rejectPendingPlans(session);
        var payload = userMessage(
                request.text(), request.attachments() == null ? List.of() : request.attachments(), session.getMode());
        if (!isIdle(session)) {
            var queued = saveItem(session, null, Item.USER_MESSAGE, Item.COMPLETED, payload);
            sessions.save(session);
            return new AcceptedMessage(queued.getId(), null, true, false);
        }
        return startMessageTurn(session, payload, idempotencyKey);
    }

    @Transactional
    public AcceptedMessage approvePlan(UUID userId, UUID sessionId, UUID itemId) {
        var session = lockOwned(userId, sessionId);
        var plan = items.findById(itemId)
                .filter(found -> found.getSessionId().equals(sessionId) && Item.PLAN.equals(found.getType()))
                .orElseThrow(() -> ApiException.notFound("plan_not_found", "Plan does not exist"));
        if (!PLAN_PENDING.equals(plan.getPayload().get("approval"))) {
            throw ApiException.conflict("plan_not_pending", "The plan was already approved or rejected");
        }
        if (!isIdle(session)) {
            throw ApiException.conflict("session_busy", "Wait until the agent finishes the current turn");
        }
        updatePayload(session, plan, Map.of("approval", PLAN_APPROVED));
        session.changeMode("agent", clock.instant());
        eventLog.append(userId, sessionId, "session.updated", SessionService.settingsPayload(session));
        var payload = userMessage(APPROVED_PLAN_MESSAGE, List.of(), session.getMode());
        payload.put("planItemId", itemId);
        return startMessageTurn(session, payload, null);
    }

    @Transactional
    public UUID startCompaction(UUID userId, UUID sessionId) {
        var session = lockOwned(userId, sessionId);
        if (!isIdle(session)) {
            throw ApiException.conflict("session_busy", "Wait until the agent finishes the current turn");
        }
        if (session.getModelId() == null) {
            throw ApiException.badRequest("model_required", "Choose a model before compacting the conversation");
        }
        return openTurn(session, null, Turn.COMPACTION).getId();
    }

    @Transactional(readOnly = true)
    public List<UUID> turnsFrom(UUID userId, UUID sessionId, UUID turnId) {
        var session = sessionService.owned(userId, sessionId);
        if (!isIdle(session)) {
            throw ApiException.conflict("session_busy", "Wait until the agent finishes the current turn");
        }
        var target = activeTurn(sessionId, turnId);
        return turns
                .findBySessionIdAndNumberGreaterThanEqualAndRewoundAtIsNullOrderByNumber(sessionId, target.getNumber())
                .stream()
                .map(Turn::getId)
                .toList();
    }

    @Transactional
    public RewindResult rewind(UUID userId, UUID sessionId, UUID turnId) {
        var session = lockOwned(userId, sessionId);
        if (!isIdle(session)) {
            throw ApiException.conflict("session_busy", "Wait until the agent finishes the current turn");
        }
        var target = activeTurn(sessionId, turnId);
        var rewound = turns.findBySessionIdAndNumberGreaterThanEqualAndRewoundAtIsNullOrderByNumber(
                sessionId, target.getNumber());
        var now = clock.instant();
        rewound.forEach(turn -> turn.markRewound(now));
        turns.saveAll(rewound);
        var turnIds = rewound.stream().map(Turn::getId).toList();
        var text = items.findBySessionIdOrderByPosition(sessionId).stream()
                .filter(item -> turnId.equals(item.getTurnId()) && Item.USER_MESSAGE.equals(item.getType()))
                .filter(item -> item.getPayload().get("planItemId") == null)
                .map(item -> String.valueOf(item.getPayload().getOrDefault("text", "")))
                .findFirst()
                .orElse("");
        eventLog.append(userId, sessionId, "session.rewound", Map.of("turnId", turnId, "turnIds", turnIds));
        changeStatus(session, SessionStatus.IDLE);
        return new RewindResult(turnIds, text);
    }

    @Transactional
    public int attachQueued(UUID userId, UUID sessionId, UUID turnId, String mode) {
        var session = lockOwned(userId, sessionId);
        var attached = 0;
        for (var item : items.findBySessionIdAndTurnIdIsNullOrderByPosition(sessionId)) {
            if (!mode.equals(item.getPayload().get("mode"))) {
                break;
            }
            attach(session, item, turnId);
            attached++;
        }
        return attached;
    }

    @Transactional
    public void resetContextTokens(UUID userId, UUID sessionId, int contextTokens, Integer contextWindow) {
        var session = lockOwned(userId, sessionId);
        session.recordUsage(contextTokens, 0, 0, clock.instant());
        sessions.save(session);
        publishUsage(session, contextWindow);
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
    public Map<String, Object> updateItem(UUID userId, UUID sessionId, UUID itemId, Map<String, Object> updates) {
        var item = items.findById(itemId)
                .filter(found -> found.getSessionId().equals(sessionId))
                .orElseThrow(() -> ApiException.notFound("item_not_found", "Item does not exist"));
        item.update(updates);
        items.save(item);
        var view = item.toView();
        eventLog.append(userId, sessionId, "item.updated", Map.of("item", view));
        return view;
    }

    @Transactional
    public void changeStatus(UUID userId, UUID sessionId, String status) {
        changeStatus(lockOwned(userId, sessionId), status);
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
        publishUsage(session, contextWindow);
    }

    @Transactional
    public Optional<UUID> finishTurn(
            UUID userId, UUID sessionId, UUID turnId, String reason, Map<String, Object> error) {
        var session = lockOwned(userId, sessionId);
        var turn = turns.findById(turnId).orElseThrow();
        closeUnfinishedItems(userId, sessionId, turnId, Turn.CANCELLED.equals(reason) ? Item.CANCELLED : Item.FAILED);
        turn.finish(reason, error, clock.instant());
        turns.save(turn);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("turnId", turnId);
        payload.put("reason", reason);
        payload.put("usage", Map.of("inputTokens", turn.getInputTokens(), "outputTokens", turn.getOutputTokens()));
        payload.put("error", error);
        eventLog.append(userId, sessionId, "turn.completed", payload);
        var queued = items.findBySessionIdAndTurnIdIsNullOrderByPosition(sessionId);
        if (Turn.COMPLETED.equals(reason) && !queued.isEmpty()) {
            var next = openTurn(session, null, Turn.MESSAGE);
            queued.forEach(item -> attach(session, item, next.getId()));
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
        return items.findVisibleBySessionId(sessionId).stream()
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
                turn.getKind(),
                turn.getMode(),
                turn.getModelId(),
                session.getContextTokens(),
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

    private void closeUnfinishedItems(UUID userId, UUID sessionId, UUID turnId, String status) {
        var now = clock.instant();
        items.findBySessionIdAndTurnIdAndStatus(sessionId, turnId, Item.IN_PROGRESS)
                .forEach(item -> {
                    var updates = Item.TOOL_CALL.equals(item.getType())
                            ? Map.<String, Object>of(
                                    "toolStatus", Item.CANCELLED.equals(status) ? "cancelled" : "failed")
                            : Map.<String, Object>of();
                    item.complete(status, updates, now);
                    items.save(item);
                    eventLog.append(userId, sessionId, "item.completed", Map.of("item", item.toView()));
                });
    }

    private AcceptedMessage startMessageTurn(AgentSession session, Map<String, Object> payload, String idempotencyKey) {
        var turn = openTurn(session, idempotencyKey, Turn.MESSAGE);
        items.findBySessionIdAndTurnIdIsNullOrderByPosition(session.getId())
                .forEach(queued -> attach(session, queued, turn.getId()));
        var item = saveItem(session, turn.getId(), Item.USER_MESSAGE, Item.COMPLETED, payload);
        return new AcceptedMessage(item.getId(), turn.getId(), false, true);
    }

    private static Map<String, Object> userMessage(String text, List<?> attachments, String mode) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("text", text);
        payload.put("attachments", attachments);
        payload.put("mode", mode);
        return payload;
    }

    private void rejectPendingPlans(AgentSession session) {
        items.findBySessionIdAndType(session.getId(), Item.PLAN).stream()
                .filter(plan -> PLAN_PENDING.equals(plan.getPayload().get("approval")))
                .forEach(plan -> updatePayload(session, plan, Map.of("approval", PLAN_REJECTED)));
    }

    private void updatePayload(AgentSession session, Item item, Map<String, Object> updates) {
        item.update(updates);
        items.save(item);
        eventLog.append(session.getUserId(), session.getId(), "item.updated", Map.of("item", item.toView()));
    }

    private void attach(AgentSession session, Item item, UUID turnId) {
        item.attachToTurn(turnId);
        items.save(item);
        eventLog.append(session.getUserId(), session.getId(), "item.updated", Map.of("item", item.toView()));
    }

    private void publishUsage(AgentSession session, Integer contextWindow) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("contextTokens", session.getContextTokens());
        payload.put("contextWindow", contextWindow);
        payload.put("totalInputTokens", session.getTotalInputTokens());
        payload.put("totalOutputTokens", session.getTotalOutputTokens());
        eventLog.append(session.getUserId(), session.getId(), "usage.updated", payload);
    }

    private Turn activeTurn(UUID sessionId, UUID turnId) {
        return turns.findById(turnId)
                .filter(turn -> turn.getSessionId().equals(sessionId) && turn.getRewoundAt() == null)
                .orElseThrow(() -> ApiException.notFound("turn_not_found", "The turn does not exist"));
    }

    private static boolean isIdle(AgentSession session) {
        return SessionStatus.IDLE.equals(session.getStatus()) || SessionStatus.ERROR.equals(session.getStatus());
    }

    private Turn openTurn(AgentSession session, String idempotencyKey, String kind) {
        var number = turns.maxNumber(session.getId()) + 1;
        var turn = turns.saveAndFlush(new Turn(
                session.getId(),
                number,
                kind,
                session.getMode(),
                session.getModelId(),
                idempotencyKey,
                clock.instant()));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("turnId", turn.getId());
        payload.put("turnKind", kind);
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
        entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE);
        return session;
    }

    public record RewindResult(List<UUID> turnIds, String text) {}

    public record AcceptedMessage(UUID itemId, UUID turnId, boolean queued, boolean startTurn) {}

    public record TurnContext(
            UUID sessionId,
            UUID userId,
            UUID projectId,
            UUID executorDeviceId,
            UUID turnId,
            int turnNumber,
            String kind,
            String mode,
            UUID modelId,
            int contextTokens,
            boolean needsTitle) {}
}
