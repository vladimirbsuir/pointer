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
import java.util.Optional;
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
        executor.submit(() -> {
            var nextTurn = Optional.of(turnId);
            while (nextTurn.isPresent()) {
                nextTurn = runTurn(userId, sessionId, nextTurn.get());
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
                    turn.cancellation);
            var tools = model.supportsTools()
                    ? toolDefinitions(userId, context.projectId(), context.mode())
                    : List.<ChatRequest.ToolDefinition>of();
            var project = projectContext(scope);
            var globalInstructions = settingsService.get(userId).globalInstructions();
            String reason = null;
            List<ChatMessage> firstMessages = null;
            for (var step = 0; reason == null; step++) {
                var messages = contextAssembler.build(
                        context.mode(), globalInstructions, project, writer.conversation(sessionId));
                if (firstMessages == null) {
                    firstMessages = messages;
                }
                var listener = new TurnListener(userId, sessionId, turnId, step, turn);
                client.stream(
                        model.endpoint(),
                        new ChatRequest(model.modelKey(), messages, tools, null),
                        listener,
                        turn.cancellation);
                listener.completeAll(turn.cancellation.isCancelled() ? Item.CANCELLED : Item.COMPLETED);
                if (listener.usage != null) {
                    writer.recordUsage(
                            userId,
                            sessionId,
                            turnId,
                            listener.usage.promptTokens(),
                            listener.usage.completionTokens(),
                            model.contextWindow());
                }
                if (turn.cancellation.isCancelled()) {
                    reason = Turn.CANCELLED;
                } else if (listener.toolCalls.isEmpty()) {
                    reason = Turn.COMPLETED;
                } else {
                    for (var call : listener.toolCalls.values()) {
                        if (turn.cancellation.isCancelled()) {
                            break;
                        }
                        toolCalls.handle(scope, step, call.id(), call.name(), call.arguments.toString());
                    }
                    if (turn.cancellation.isCancelled()) {
                        reason = Turn.CANCELLED;
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

    private List<ChatRequest.ToolDefinition> toolDefinitions(UUID userId, UUID projectId, String mode) {
        return toolRegistry.available(userId, projectId).stream()
                .filter(tool -> tool.availableIn(mode))
                .map(tool -> new ChatRequest.ToolDefinition(
                        tool.getName(), tool.getDescription(), tool.getParametersSchema()))
                .toList();
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
                        PROJECT_CONTEXT_TIMEOUT),
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
        return writer.finishTurn(
                userId, sessionId, turnId, reason, cancelled ? null : Map.of("code", code, "message", message));
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

    private final class TurnListener implements StreamListener {

        private final UUID userId;
        private final UUID sessionId;
        private final UUID turnId;
        private final int step;
        private final RunningTurn turn;
        private final Map<Integer, PendingToolCall> toolCalls = new TreeMap<>();
        private UUID assistantItemId;
        private UUID reasoningItemId;
        private Usage usage;

        TurnListener(UUID userId, UUID sessionId, UUID turnId, int step, RunningTurn turn) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.step = step;
            this.turn = turn;
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
                assistantItemId = open(Item.ASSISTANT_MESSAGE);
            }
            append(assistantItemId, delta);
        }

        @Override
        public void onReasoning(String delta) {
            if (reasoningItemId == null) {
                reasoningItemId = open(Item.REASONING);
            }
            append(reasoningItemId, delta);
        }

        @Override
        public void onUsage(Usage usage) {
            this.usage = usage;
        }

        void completeAll(String status) {
            for (var entry : new ArrayList<>(turn.items.entrySet())) {
                writer.completeItem(
                        userId,
                        sessionId,
                        entry.getKey(),
                        status,
                        Map.of("text", entry.getValue().text.toString()));
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
