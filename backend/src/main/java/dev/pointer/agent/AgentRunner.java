package dev.pointer.agent;

import dev.pointer.common.error.ApiException;
import dev.pointer.execution.ExecutionListener;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionService;
import dev.pointer.project.ProjectService;
import dev.pointer.provider.ProviderService;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.provider.llm.ChatRequest;
import dev.pointer.provider.llm.LlmException;
import dev.pointer.provider.llm.OpenAiCompatibleClient;
import dev.pointer.provider.llm.StreamListener;
import dev.pointer.session.Item;
import dev.pointer.session.LiveItemSnapshots;
import dev.pointer.session.SessionDtos;
import dev.pointer.session.SessionEventBus;
import dev.pointer.session.SessionService;
import dev.pointer.session.SessionWriter;
import dev.pointer.session.Turn;
import dev.pointer.tool.ToolRegistry;
import dev.pointer.user.SettingsService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class AgentRunner implements LiveItemSnapshots {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int MAX_STEPS = 50;
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final double AUTO_COMPACT_RATIO = 0.8;
    private static final String PROJECT_CONTEXT_OPERATION = "pointer.project_context";
    private static final Duration PROJECT_CONTEXT_TIMEOUT = Duration.ofSeconds(10);
    private static final String TITLE_PROMPT = """
            Write a concise title of at most six words for a conversation that starts with the user message below.
            Use the language of the message. Reply with the title only, without quotes or punctuation at the end.""";

    private final SessionWriter writer;
    private final SessionService sessionService;
    private final ProviderService providerService;
    private final SettingsService settingsService;
    private final OpenAiCompatibleClient client;
    private final ContextAssembler contextAssembler;
    private final SessionEventBus bus;
    private final ToolCallHandler toolCalls;
    private final ToolRegistry toolRegistry;
    private final ProjectService projectService;
    private final ExecutionService execution;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, RunningTurn> running = new ConcurrentHashMap<>();

    AgentRunner(
            SessionWriter writer,
            SessionService sessionService,
            ProviderService providerService,
            SettingsService settingsService,
            OpenAiCompatibleClient client,
            ContextAssembler contextAssembler,
            SessionEventBus bus,
            ToolCallHandler toolCalls,
            ToolRegistry toolRegistry,
            ProjectService projectService,
            ExecutionService execution) {
        this.writer = writer;
        this.sessionService = sessionService;
        this.providerService = providerService;
        this.settingsService = settingsService;
        this.client = client;
        this.contextAssembler = contextAssembler;
        this.bus = bus;
        this.toolCalls = toolCalls;
        this.toolRegistry = toolRegistry;
        this.projectService = projectService;
        this.execution = execution;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverInterruptedTurns() {
        var count = writer.recoverInterrupted();
        if (count > 0) {
            log.info("Marked {} running turns as interrupted after restart", count);
        }
    }

    public SessionDtos.SendMessageResponse send(
            UUID userId, UUID sessionId, SessionDtos.SendMessageRequest request, String idempotencyKey) {
        var accepted = writer.acceptUserMessage(userId, sessionId, request, idempotencyKey);
        if (accepted.startTurn()) {
            launch(userId, sessionId, accepted.turnId());
        }
        return new SessionDtos.SendMessageResponse(accepted.itemId(), accepted.turnId(), accepted.queued());
    }

    public SessionDtos.SendMessageResponse approvePlan(UUID userId, UUID sessionId, UUID itemId) {
        var accepted = writer.approvePlan(userId, sessionId, itemId);
        launch(userId, sessionId, accepted.turnId());
        return new SessionDtos.SendMessageResponse(accepted.itemId(), accepted.turnId(), false);
    }

    public void compact(UUID userId, UUID sessionId) {
        launch(userId, sessionId, writer.startCompaction(userId, sessionId));
    }

    public void cancel(UUID userId, UUID sessionId) {
        sessionService.owned(userId, sessionId);
        var turn = running.get(sessionId);
        if (turn != null) {
            turn.cancellation.cancel();
        }
    }

    @Override
    public List<Map<String, Object>> snapshots(UUID sessionId) {
        var turn = running.get(sessionId);
        return turn == null ? List.of() : turn.snapshots();
    }

    @PreDestroy
    void shutdown() {
        running.values().forEach(turn -> turn.cancellation.cancel());
        executor.shutdownNow();
    }

    private void launch(UUID userId, UUID sessionId, UUID turnId) {
        executor.execute(() -> {
            try {
                var nextTurn = Optional.of(turnId);
                while (nextTurn.isPresent()) {
                    nextTurn = runTurn(userId, sessionId, nextTurn.get());
                }
            } catch (Throwable throwable) {
                log.error("Agent loop for session {} stopped unexpectedly", sessionId, throwable);
            }
        });
    }

    private Optional<UUID> runTurn(UUID userId, UUID sessionId, UUID turnId) {
        var turn = new RunningTurn(sessionId);
        running.put(sessionId, turn);
        try {
            var context = writer.turnContext(userId, sessionId, turnId);
            var model = providerService.resolve(userId, context.modelId(), context.executorDeviceId());
            var scope = new ToolCallHandler.TurnScope(
                    userId,
                    sessionId,
                    context.projectId(),
                    context.executorDeviceId(),
                    turnId,
                    context.mode(),
                    context.worktree(),
                    turn.cancellation);
            var project = projectContext(scope);
            var globalInstructions = settingsService.get(userId).globalInstructions();
            var summarizer = new Summarizer(scope, model, turn, project, globalInstructions);
            if (Turn.COMPACTION.equals(context.kind())) {
                summarizer.summarize();
                var reason = turn.cancellation.isCancelled() ? Turn.CANCELLED : Turn.COMPLETED;
                return writer.finishTurn(userId, sessionId, turnId, reason, null);
            }
            var tools = model.supportsTools()
                    ? toolDefinitions(userId, context.projectId(), context.executorDeviceId(), context.mode())
                    : List.<ChatRequest.ToolDefinition>of();
            var contextWindow = model.contextWindow() == null ? DEFAULT_CONTEXT_WINDOW : model.contextWindow();
            long contextTokens = context.contextTokens();
            String reason = null;
            List<ChatMessage> firstMessages = null;
            for (var step = 0; reason == null; step++) {
                if (step > 0) {
                    writer.attachQueued(userId, sessionId, turnId, context.mode());
                }
                if (contextTokens >= contextWindow * AUTO_COMPACT_RATIO) {
                    var compacted = summarizer.summarize();
                    if (compacted.isPresent()) {
                        contextTokens = compacted.getAsInt();
                    }
                    if (turn.cancellation.isCancelled()) {
                        reason = Turn.CANCELLED;
                        break;
                    }
                }
                var messages = contextAssembler.build(
                        context.mode(),
                        globalInstructions,
                        project,
                        writer.conversation(sessionId),
                        model.supportsVision());
                if (firstMessages == null) {
                    firstMessages = messages;
                }
                var listener = new TurnListener(userId, sessionId, turnId, step, turn, Item.ASSISTANT_MESSAGE);
                client.stream(
                        model.endpoint(),
                        new ChatRequest(model.modelKey(), messages, tools, null),
                        listener,
                        turn.cancellation);
                listener.completeAll(turn.cancellation.isCancelled() ? Item.CANCELLED : Item.COMPLETED, Map.of());
                contextTokens = recordUsage(scope, model, listener.usage, messages);
                if (turn.cancellation.isCancelled()) {
                    reason = Turn.CANCELLED;
                } else if (listener.toolCalls.isEmpty()) {
                    var delivered = writer.attachQueued(userId, sessionId, turnId, context.mode());
                    reason = delivered > 0 ? null : Turn.COMPLETED;
                } else {
                    var planProposed = false;
                    for (var call : listener.toolCalls.values()) {
                        if (turn.cancellation.isCancelled()) {
                            break;
                        }
                        if (PlanTool.NAME.equals(call.name()) && "plan".equals(context.mode())) {
                            planProposed |= toolCalls.proposePlan(scope, step, call.id(), call.arguments.toString());
                        } else {
                            toolCalls.handle(scope, step, call.id(), call.name(), call.arguments.toString());
                        }
                    }
                    if (turn.cancellation.isCancelled()) {
                        reason = Turn.CANCELLED;
                    } else if (planProposed) {
                        reason = Turn.COMPLETED;
                    } else if (step + 1 >= MAX_STEPS) {
                        reason = Turn.STEP_LIMIT;
                    }
                }
            }
            if (context.needsTitle() && !Turn.CANCELLED.equals(reason)) {
                var titleMessages = firstMessages;
                executor.submit(() -> generateTitle(userId, sessionId, model, titleMessages));
            }
            return writer.finishTurn(userId, sessionId, turnId, reason, null);
        } catch (LlmException exception) {
            return fail(userId, sessionId, turnId, turn, exception.code(), exception.getMessage());
        } catch (ApiException exception) {
            return fail(userId, sessionId, turnId, turn, exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Turn {} failed", turnId, exception);
            return fail(userId, sessionId, turnId, turn, "internal_error", "Unexpected error while running the agent");
        } finally {
            running.remove(sessionId, turn);
        }
    }

    private List<ChatRequest.ToolDefinition> toolDefinitions(UUID userId, UUID projectId, UUID deviceId, String mode) {
        var definitions = new ArrayList<ChatRequest.ToolDefinition>();
        toolRegistry.available(userId, projectId, deviceId).stream()
                .filter(tool -> tool.availableIn(mode))
                .map(tool -> new ChatRequest.ToolDefinition(
                        tool.getName(), tool.getDescription(), tool.getParametersSchema()))
                .forEach(definitions::add);
        if ("plan".equals(mode)) {
            definitions.add(PlanTool.DEFINITION);
        }
        return definitions;
    }

    private int recordUsage(
            ToolCallHandler.TurnScope scope, ResolvedModel model, StreamListener.Usage usage, List<ChatMessage> sent) {
        if (usage != null) {
            writer.recordUsage(
                    scope.userId(),
                    scope.sessionId(),
                    scope.turnId(),
                    usage.promptTokens(),
                    usage.completionTokens(),
                    model.cost(usage.promptTokens(), usage.completionTokens()),
                    model.contextWindow());
            return (int) (usage.promptTokens() + usage.completionTokens());
        }
        var estimate = ContextAssembler.estimateTokens(sent);
        writer.resetContextTokens(scope.userId(), scope.sessionId(), estimate, model.contextWindow());
        return estimate;
    }

    private ContextAssembler.ProjectContext projectContext(ToolCallHandler.TurnScope scope) {
        var name = projectService.owned(scope.userId(), scope.projectId()).getName();
        if (!execution.canExecute(scope.deviceId(), scope.projectId())) {
            return new ContextAssembler.ProjectContext(name, null, null, null);
        }
        var result = execution.execute(
                new ExecutionRequest(
                        scope.sessionId(),
                        scope.projectId(),
                        scope.deviceId(),
                        PROJECT_CONTEXT_OPERATION,
                        Map.of(),
                        false,
                        PROJECT_CONTEXT_TIMEOUT,
                        scope.worktree()),
                new ExecutionListener() {},
                scope.cancellation());
        if (!result.succeeded()) {
            return new ContextAssembler.ProjectContext(name, null, null, null);
        }
        var branch = result.result().get("gitBranch") instanceof String value ? value : null;
        if (result.result().get("rulesFile") instanceof Map<?, ?> rules) {
            return new ContextAssembler.ProjectContext(
                    name, branch, String.valueOf(rules.get("name")), String.valueOf(rules.get("content")));
        }
        return new ContextAssembler.ProjectContext(name, branch, null, null);
    }

    private Optional<UUID> fail(
            UUID userId, UUID sessionId, UUID turnId, RunningTurn turn, String code, String message) {
        turn.items.forEach((itemId, live) ->
                writer.completeItem(userId, sessionId, itemId, Item.FAILED, Map.of("text", live.text.toString())));
        var cancelled = turn.cancellation.isCancelled();
        var reason = cancelled ? Turn.CANCELLED : Turn.FAILED;
        var error = cancelled
                ? null
                : Map.<String, Object>of(
                        "code",
                        Objects.requireNonNullElse(code, "internal_error"),
                        "message",
                        Objects.requireNonNullElse(message, "The model request failed"));
        return writer.finishTurn(userId, sessionId, turnId, reason, error);
    }

    private void generateTitle(UUID userId, UUID sessionId, ResolvedModel model, List<ChatMessage> messages) {
        try {
            var firstUserMessage = messages.stream()
                    .filter(message -> message.role() == ChatMessage.Role.USER)
                    .map(ChatMessage::content)
                    .findFirst()
                    .orElse("");
            var title = new StringBuilder();
            client.stream(
                    model.endpoint(),
                    new ChatRequest(
                            model.modelKey(),
                            List.of(ChatMessage.system(TITLE_PROMPT), ChatMessage.user(firstUserMessage)),
                            List.of(),
                            null),
                    new StreamListener() {
                        @Override
                        public void onText(String delta) {
                            title.append(delta);
                        }
                    },
                    new Cancellation());
            var cleaned = cleanTitle(title.toString());
            if (!cleaned.isEmpty()) {
                writer.applyGeneratedTitle(userId, sessionId, cleaned);
            }
        } catch (RuntimeException exception) {
            log.warn("Title generation for session {} failed: {}", sessionId, exception.getMessage());
        }
    }

    static String cleanTitle(String raw) {
        var title = raw.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceAll("^[\"'«“#*\\s]+|[\"'»”*.\\s]+$", "");
        return title.length() > MAX_TITLE_LENGTH
                ? title.substring(0, MAX_TITLE_LENGTH).strip()
                : title;
    }

    private final class Summarizer {

        private final ToolCallHandler.TurnScope scope;
        private final ResolvedModel model;
        private final RunningTurn turn;
        private final ContextAssembler.ProjectContext project;
        private final String globalInstructions;

        Summarizer(
                ToolCallHandler.TurnScope scope,
                ResolvedModel model,
                RunningTurn turn,
                ContextAssembler.ProjectContext project,
                String globalInstructions) {
            this.scope = scope;
            this.model = model;
            this.turn = turn;
            this.project = project;
            this.globalInstructions = globalInstructions;
        }

        OptionalInt summarize() {
            var covered = ContextAssembler.compactable(writer.conversation(scope.sessionId()), scope.turnId());
            if (covered.isEmpty()) {
                return OptionalInt.empty();
            }
            var replaced = covered.stream()
                    .filter(item -> !Item.COMPACTION_SUMMARY.equals(item.get("type")))
                    .count();
            var listener = new TurnListener(
                    scope.userId(), scope.sessionId(), scope.turnId(), 0, turn, Item.COMPACTION_SUMMARY);
            client.stream(
                    model.endpoint(),
                    new ChatRequest(model.modelKey(), contextAssembler.buildCompaction(covered), List.of(), null),
                    listener,
                    turn.cancellation);
            if (turn.cancellation.isCancelled()) {
                listener.completeAll(Item.CANCELLED, Map.of());
                return OptionalInt.empty();
            }
            if (listener.text().isBlank()) {
                listener.completeAll(Item.FAILED, Map.of());
                throw new LlmException("compaction_failed", "The model returned an empty summary", 0);
            }
            listener.completeAll(Item.COMPLETED, Map.of("replacedItemCount", replaced));
            if (listener.usage != null) {
                writer.recordUsage(
                        scope.userId(),
                        scope.sessionId(),
                        scope.turnId(),
                        listener.usage.promptTokens(),
                        listener.usage.completionTokens(),
                        model.cost(listener.usage.promptTokens(), listener.usage.completionTokens()),
                        model.contextWindow());
            }
            var remaining = contextAssembler.build(
                    scope.mode(),
                    globalInstructions,
                    project,
                    writer.conversation(scope.sessionId()),
                    model.supportsVision());
            var tokens = ContextAssembler.estimateTokens(remaining);
            writer.resetContextTokens(scope.userId(), scope.sessionId(), tokens, model.contextWindow());
            return OptionalInt.of(tokens);
        }
    }

    private final class TurnListener implements StreamListener {

        private final UUID userId;
        private final UUID sessionId;
        private final UUID turnId;
        private final int step;
        private final RunningTurn turn;
        private final String textItemType;
        private final Map<Integer, PendingToolCall> toolCalls = new TreeMap<>();
        private UUID assistantItemId;
        private UUID reasoningItemId;
        private Usage usage;

        TurnListener(UUID userId, UUID sessionId, UUID turnId, int step, RunningTurn turn, String textItemType) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.step = step;
            this.turn = turn;
            this.textItemType = textItemType;
        }

        @Override
        public void onToolCallDelta(int index, String id, String name, String argumentsDelta) {
            var call = toolCalls.computeIfAbsent(index, ignored -> new PendingToolCall());
            if (id != null) {
                call.id = id;
            }
            if (name != null) {
                call.name = name;
            }
            call.arguments.append(argumentsDelta);
        }

        @Override
        public void onText(String delta) {
            if (assistantItemId == null) {
                assistantItemId = open(textItemType);
            }
            append(assistantItemId, delta);
        }

        @Override
        public void onReasoning(String delta) {
            if (!Item.ASSISTANT_MESSAGE.equals(textItemType)) {
                return;
            }
            if (reasoningItemId == null) {
                reasoningItemId = open(Item.REASONING);
            }
            append(reasoningItemId, delta);
        }

        @Override
        public void onUsage(Usage usage) {
            this.usage = usage;
        }

        String text() {
            var item = assistantItemId == null ? null : turn.items.get(assistantItemId);
            return item == null ? "" : item.text.toString();
        }

        void completeAll(String status, Map<String, Object> extra) {
            for (var entry : new ArrayList<>(turn.items.entrySet())) {
                var updates = new LinkedHashMap<String, Object>(extra);
                updates.put("text", entry.getValue().text.toString());
                writer.completeItem(userId, sessionId, entry.getKey(), status, updates);
            }
            turn.items.clear();
        }

        private UUID open(String type) {
            var view = writer.createItem(userId, sessionId, turnId, type, Map.of("text", "", "step", step));
            var itemId = (UUID) view.get("id");
            turn.items.put(itemId, new LiveItem(view));
            return itemId;
        }

        private void append(UUID itemId, String delta) {
            turn.items.get(itemId).text.append(delta);
            bus.publishDelta(userId, sessionId, itemId, "text", delta);
        }
    }

    private static final class PendingToolCall {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();

        String id() {
            return id == null ? "call_" + UUID.randomUUID() : id;
        }

        String name() {
            return name == null ? "" : name;
        }
    }

    private static final class RunningTurn {

        final UUID sessionId;
        final Cancellation cancellation = new Cancellation();
        final Map<UUID, LiveItem> items = new ConcurrentHashMap<>();

        RunningTurn(UUID sessionId) {
            this.sessionId = sessionId;
        }

        List<Map<String, Object>> snapshots() {
            return items.values().stream().map(LiveItem::snapshot).toList();
        }
    }

    private static final class LiveItem {

        final Map<String, Object> view;
        final StringBuffer text = new StringBuffer();

        LiveItem(Map<String, Object> view) {
            this.view = view;
        }

        Map<String, Object> snapshot() {
            var snapshot = new LinkedHashMap<>(view);
            snapshot.put("text", text.toString());
            return snapshot;
        }
    }
}
