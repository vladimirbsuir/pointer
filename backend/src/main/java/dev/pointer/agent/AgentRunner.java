package dev.pointer.agent;

import dev.pointer.common.error.ApiException;
import dev.pointer.customtool.ProposeTool;
import dev.pointer.execution.ExecutionListener;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionService;
import dev.pointer.memory.MemoryPrompt;
import dev.pointer.project.ProjectService;
import dev.pointer.provider.ProviderService;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.provider.llm.ChatRequest;
import dev.pointer.provider.llm.LlmException;
import dev.pointer.provider.llm.StreamListener;
import dev.pointer.provider.llm.TokenEstimate;
import dev.pointer.routing.CompactionPlanner;
import dev.pointer.routing.ModelRouter;
import dev.pointer.routing.RoutedClient;
import dev.pointer.session.Item;
import dev.pointer.session.LiveItemSnapshots;
import dev.pointer.session.SessionDtos;
import dev.pointer.session.SessionEventBus;
import dev.pointer.session.SessionService;
import dev.pointer.session.SessionWriter;
import dev.pointer.session.Turn;
import dev.pointer.session.UsageLedger;
import dev.pointer.tool.ToolRegistry;
import dev.pointer.user.SettingsService;
import jakarta.annotation.PreDestroy;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentRunner implements LiveItemSnapshots {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int MAX_STEPS = 50;
    private static final int MAX_SWITCH_REASON_LENGTH = 300;
    private static final String AGENT = "agent";
    private static final String READ_FILE = "read_file";
    private static final String TRIGGER_MANUAL = "manual";
    private static final String TRIGGER_LIMIT = "limit";
    private static final String TRIGGER_SOFT = "soft";
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final String PROJECT_CONTEXT_OPERATION = "pointer.project_context";
    private static final Duration PROJECT_CONTEXT_TIMEOUT = Duration.ofSeconds(10);
    private static final String TITLE_PROMPT = """
            Write a concise title of at most six words for a conversation that starts with the user message below.
            Use the language of the message. Reply with the title only, without quotes or punctuation at the end.""";

    private final SessionWriter writer;
    private final SessionService sessionService;
    private final ProviderService providerService;
    private final SettingsService settingsService;
    private final RoutedClient routedClient;
    private final ContextAssembler contextAssembler;
    private final SessionEventBus bus;
    private final ToolCallHandler toolCalls;
    private final ToolRegistry toolRegistry;
    private final ProjectService projectService;
    private final ExecutionService execution;
    private final MemoryPrompt memoryPrompt;
    private final UsageLedger ledger;
    private final ModelRouter modelRouter;
    private final ExploreAgent explore;
    private final ContextPolicy contextPolicy;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, RunningTurn> running = new ConcurrentHashMap<>();

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
            var main = providerService.resolve(userId, context.modelId(), context.executorDeviceId());
            var session = sessionService.owned(userId, sessionId);
            var smart = session.isSmartRouting();
            var mask = contextPolicy.mask(session.getContextStrategy());
            var compactionRatio = contextPolicy.compactionRatio(session.getContextStrategy());
            var routes = modelRouter.plan(userId, smart, main, context.mode(), context.executorDeviceId());
            var failover = routedClient.failover(
                    userId,
                    context.executorDeviceId(),
                    smart,
                    (from, to, failure) -> noteModelSwitch(userId, sessionId, turnId, from, to, failure));
            var model = routes.step().model();
            var scope = new ToolCallHandler.TurnScope(
                    userId,
                    sessionId,
                    context.projectId(),
                    context.executorDeviceId(),
                    turnId,
                    context.mode(),
                    context.worktree(),
                    turn.cancellation);
            var project = projectContext(scope).withMemory(memorySection(scope));
            if (!Turn.COMPACTION.equals(context.kind())) {
                writer.recordContextSnapshot(sessionId, turnId, contextAssembler.snapshot(project));
            }
            var globalInstructions = settingsService.get(userId).globalInstructions();
            Supplier<List<ChatRequest.ToolDefinition>> stepTools = () -> model.supportsTools()
                    ? toolDefinitions(userId, context.projectId(), context.executorDeviceId(), context.mode(), routes)
                    : List.of();
            var summarizer =
                    new Summarizer(scope, routes, failover, turn, project, globalInstructions, mask, stepTools);
            var softCompaction = contextPolicy.softCompaction(session.getContextStrategy());
            if (Turn.COMPACTION.equals(context.kind())) {
                summarizer.summarize(TRIGGER_MANUAL);
                var reason = turn.cancellation.isCancelled() ? Turn.CANCELLED : Turn.COMPLETED;
                return writer.finishTurn(userId, sessionId, turnId, reason, null);
            }
            var contextWindow = model.contextWindow() == null ? DEFAULT_CONTEXT_WINDOW : model.contextWindow();
            long contextTokens = context.contextTokens();
            String reason = null;
            Map<String, Object> error = null;
            for (var step = 0; reason == null; step++) {
                if (step > 0) {
                    writer.attachQueued(userId, sessionId, turnId, context.mode());
                }
                var budget = budgetError(userId, sessionId);
                if (budget != null) {
                    reason = Turn.BUDGET_EXCEEDED;
                    error = budget;
                    break;
                }
                var compacted = OptionalInt.empty();
                if (contextTokens >= contextWindow * compactionRatio) {
                    compacted = summarizer.summarize(TRIGGER_LIMIT);
                } else if (step == 0 && softCompaction) {
                    compacted = summarizer.summarizeIfWorthIt(contextTokens, contextWindow);
                }
                if (compacted.isPresent()) {
                    contextTokens = compacted.getAsInt();
                }
                if (turn.cancellation.isCancelled()) {
                    reason = Turn.CANCELLED;
                    break;
                }
                var messages = contextAssembler.build(
                        context.mode(),
                        globalInstructions,
                        project,
                        writer.conversation(sessionId),
                        writer.contextSnapshots(sessionId),
                        mask,
                        model.supportsVision());
                var tools = stepTools.get();
                var listener = new TurnListener(userId, sessionId, turnId, step, turn, Item.ASSISTANT_MESSAGE);
                var request = new ChatRequest(model.modelKey(), messages, tools, null).withCacheKey(sessionId);
                var used = failover.stream(routes.step(), request, listener, turn.cancellation);
                listener.completeAll(turn.cancellation.isCancelled() ? Item.CANCELLED : Item.COMPLETED, Map.of());
                contextTokens = recordUsage(scope, used, model.contextWindow(), listener.usage, request);
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
                        } else if (ProposeTool.NAME.equals(call.name()) && AGENT.equals(context.mode())) {
                            toolCalls.proposeTool(scope, step, call.id(), call.arguments.toString());
                        } else if (ExploreAgent.NAME.equals(call.name()) && ExploreAgent.offered(routes)) {
                            explore.run(
                                    scope,
                                    step,
                                    call.id(),
                                    call.arguments.toString(),
                                    routes.explore(),
                                    failover,
                                    project);
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
                var firstMessage = sessionService.turnMessage(sessionId, turnId).orElse("");
                var titleFailover = routedClient.failover(userId, context.executorDeviceId(), smart);
                executor.submit(
                        () -> generateTitle(userId, sessionId, turnId, routes.fast(), titleFailover, firstMessage));
            }
            return writer.finishTurn(userId, sessionId, turnId, reason, error);
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

    private List<ChatRequest.ToolDefinition> toolDefinitions(
            UUID userId, UUID projectId, UUID deviceId, String mode, ModelRouter.ModelPlan routes) {
        var definitions = new ArrayList<ChatRequest.ToolDefinition>();
        toolRegistry.available(userId, projectId, deviceId).stream()
                .filter(tool -> tool.availableIn(mode))
                .map(tool -> new ChatRequest.ToolDefinition(
                        tool.getName(), tool.getDescription(), tool.getParametersSchema()))
                .forEach(definitions::add);
        if ("plan".equals(mode)) {
            definitions.add(PlanTool.DEFINITION);
        }
        if (ExploreAgent.offered(routes) && definitions.stream().anyMatch(tool -> READ_FILE.equals(tool.name()))) {
            definitions.add(ExploreAgent.DEFINITION);
        }
        if (AGENT.equals(mode)) {
            execution
                    .interpreters(deviceId)
                    .filter(interpreters -> !interpreters.isEmpty())
                    .ifPresent(interpreters -> definitions.add(ProposeTool.definition(interpreters)));
        }
        return definitions;
    }

    private int recordUsage(
            ToolCallHandler.TurnScope scope,
            ModelRouter.RoutedModel used,
            Integer contextWindow,
            StreamListener.Usage usage,
            ChatRequest sent) {
        var model = used.model();
        ledger.record(scope.sessionId(), scope.turnId(), model, used.role(), UsageLedger.STEP, sent, usage);
        if (usage != null) {
            writer.recordUsage(
                    scope.userId(),
                    scope.sessionId(),
                    scope.turnId(),
                    usage.promptTokens(),
                    usage.completionTokens(),
                    model.cost(usage),
                    contextWindow);
            return (int) (usage.promptTokens() + usage.completionTokens());
        }
        var estimate = ContextAssembler.estimateTokens(sent.messages());
        writer.resetContextTokens(scope.userId(), scope.sessionId(), estimate, contextWindow);
        return estimate;
    }

    private void noteModelSwitch(
            UUID userId, UUID sessionId, UUID turnId, ResolvedModel from, ResolvedModel to, LlmException failure) {
        var reason = Objects.requireNonNullElse(failure.getMessage(), failure.code());
        writer.appendNotice(
                userId,
                sessionId,
                turnId,
                Item.MODEL_SWITCHED,
                Map.of(
                        "from",
                        from.displayName(),
                        "to",
                        to.displayName(),
                        "reason",
                        reason.length() > MAX_SWITCH_REASON_LENGTH
                                ? reason.substring(0, MAX_SWITCH_REASON_LENGTH)
                                : reason));
    }

    private String memorySection(ToolCallHandler.TurnScope scope) {
        try {
            var query = sessionService
                    .turnMessage(scope.sessionId(), scope.turnId())
                    .orElse(null);
            return memoryPrompt.section(scope.userId(), scope.projectId(), query, scope.deviceId());
        } catch (RuntimeException exception) {
            log.warn("Building the memory section for session {} failed", scope.sessionId(), exception);
            return null;
        }
    }

    private ContextAssembler.ProjectContext projectContext(ToolCallHandler.TurnScope scope) {
        var name = projectService.owned(scope.userId(), scope.projectId()).getName();
        if (!execution.canExecute(scope.deviceId(), scope.projectId()) && !execution.isManaged(scope.deviceId())) {
            return new ContextAssembler.ProjectContext(name);
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
            return new ContextAssembler.ProjectContext(name);
        }
        var branch = text(result.result().get("gitBranch"));
        var os = text(result.result().get("os"));
        var shell = text(result.result().get("shell"));
        if (result.result().get("rulesFile") instanceof Map<?, ?> rules) {
            return new ContextAssembler.ProjectContext(
                    name, branch, String.valueOf(rules.get("name")), String.valueOf(rules.get("content")), os, shell);
        }
        return new ContextAssembler.ProjectContext(name, branch, null, null, os, shell);
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
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

    private Map<String, Object> budgetError(UUID userId, UUID sessionId) {
        var session = sessionService.owned(userId, sessionId);
        if (!session.isOverBudget()) {
            return null;
        }
        return Map.of(
                "code",
                Turn.BUDGET_EXCEEDED,
                "message",
                "The session budget of $"
                        + session.getBudgetUsd().stripTrailingZeros().toPlainString()
                        + " is used up: $"
                        + session.getEstimatedCost()
                                .setScale(4, RoundingMode.HALF_UP)
                                .stripTrailingZeros()
                                .toPlainString()
                        + " spent. Raise or clear the budget to continue.");
    }

    private void generateTitle(
            UUID userId,
            UUID sessionId,
            UUID turnId,
            ModelRouter.RoutedModel routed,
            RoutedClient.Failover failover,
            String firstUserMessage) {
        try {
            var title = new StringBuilder();
            var reported = new AtomicReference<StreamListener.Usage>();
            var request = new ChatRequest(
                            routed.model().modelKey(),
                            List.of(ChatMessage.system(TITLE_PROMPT), ChatMessage.user(firstUserMessage)),
                            List.of(),
                            null)
                    .withCacheKey(sessionId);
            var used = failover.stream(
                    routed,
                    request,
                    new StreamListener() {
                        @Override
                        public void onText(String delta) {
                            title.append(delta);
                        }

                        @Override
                        public void onUsage(Usage usage) {
                            reported.set(usage);
                        }
                    },
                    new Cancellation());
            ledger.record(sessionId, turnId, used.model(), used.role(), UsageLedger.TITLE, request, reported.get());
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
        private final ModelRouter.ModelPlan routes;
        private final RoutedClient.Failover failover;
        private final ResolvedModel model;
        private final RunningTurn turn;
        private final ContextAssembler.ProjectContext project;
        private final String globalInstructions;
        private final MaskPolicy mask;
        private final Supplier<List<ChatRequest.ToolDefinition>> tools;

        Summarizer(
                ToolCallHandler.TurnScope scope,
                ModelRouter.ModelPlan routes,
                RoutedClient.Failover failover,
                RunningTurn turn,
                ContextAssembler.ProjectContext project,
                String globalInstructions,
                MaskPolicy mask,
                Supplier<List<ChatRequest.ToolDefinition>> tools) {
            this.scope = scope;
            this.routes = routes;
            this.failover = failover;
            this.model = routes.step().model();
            this.turn = turn;
            this.project = project;
            this.globalInstructions = globalInstructions;
            this.mask = mask;
            this.tools = tools;
        }

        OptionalInt summarize(String trigger) {
            var prepared = prepare();
            return prepared == null ? OptionalInt.empty() : compact(prepared, trigger);
        }

        OptionalInt summarizeIfWorthIt(long contextTokens, int contextWindow) {
            if (!CompactionPlanner.softTriggerApplies(model, contextTokens, contextWindow)) {
                return OptionalInt.empty();
            }
            var prepared = prepare();
            if (prepared == null
                    || !CompactionPlanner.worthCompacting(routes, prepared.estimate(), contextTokens, contextWindow)) {
                return OptionalInt.empty();
            }
            return compact(prepared, TRIGGER_SOFT);
        }

        private Prepared prepare() {
            var conversation = writer.conversation(scope.sessionId());
            var covered = ContextAssembler.compactable(conversation, scope.turnId());
            if (covered.isEmpty()) {
                return null;
            }
            var legacy = contextAssembler.buildCompaction(covered, mask);
            var cacheSafe = contextAssembler.buildCacheSafeCompaction(
                    scope.mode(),
                    globalInstructions,
                    project,
                    conversation,
                    writer.contextSnapshots(scope.sessionId()),
                    mask,
                    model.supportsVision(),
                    scope.turnId());
            var definitions = tools.get();
            var cacheSafeTokens = ContextAssembler.estimateTokens(cacheSafe) + TokenEstimate.tools(definitions);
            var history = ledger.compactionHistory(scope.sessionId());
            var estimate = new CompactionPlanner.Estimate(
                    ContextAssembler.estimateTokens(legacy),
                    cacheSafeTokens,
                    cacheSafeTokens - TokenEstimate.message(cacheSafe.getLast()),
                    ContextAssembler.estimateTokens(cacheSafe.subList(1, cacheSafe.size() - 1)),
                    history.averageSummaryTokens() == null
                            ? CompactionPlanner.DEFAULT_SUMMARY_TOKENS
                            : (int) Math.round(history.averageSummaryTokens()),
                    Objects.requireNonNullElse(history.averageCallsPerTurn(), CompactionPlanner.DEFAULT_CALLS_PER_TURN),
                    isCacheWarm(history.lastCallAt()));
            var replaced = covered.stream()
                    .filter(item -> !Item.COMPACTION_SUMMARY.equals(item.get("type")))
                    .count();
            return new Prepared(legacy, cacheSafe, definitions, estimate, replaced);
        }

        private boolean isCacheWarm(Instant lastCallAt) {
            return lastCallAt != null
                    && Duration.between(lastCallAt, clock.instant()).compareTo(contextPolicy.cacheLifetime()) <= 0;
        }

        private ChatRequest request(ModelRouter.RoutedModel target, boolean cacheSafe, Prepared prepared) {
            var request = cacheSafe
                    ? new ChatRequest(target.model().modelKey(), prepared.cacheSafe(), prepared.tools(), null)
                    : new ChatRequest(target.model().modelKey(), prepared.legacy(), List.of(), null);
            return request.withCacheKey(scope.sessionId());
        }

        private TurnListener listener() {
            return new TurnListener(
                    scope.userId(), scope.sessionId(), scope.turnId(), 0, turn, Item.COMPACTION_SUMMARY);
        }

        private OptionalInt compact(Prepared prepared, String trigger) {
            var choice = CompactionPlanner.choose(routes, prepared.estimate());
            var target = choice.target();
            var cacheSafe = choice.cacheSafe();
            var listener = listener();
            ModelRouter.RoutedModel routed;
            try {
                routed = failover.stream(target, request(target, cacheSafe, prepared), listener, turn.cancellation);
            } catch (LlmException exception) {
                if (target == routes.step() || !listener.text().isEmpty() || turn.cancellation.isCancelled()) {
                    throw exception;
                }
                log.warn(
                        "Compaction with {} failed, retrying with the step model: {}",
                        target.model().modelKey(),
                        exception.getMessage());
                target = routes.step();
                cacheSafe = true;
                listener = listener();
                routed = failover.stream(target, request(target, true, prepared), listener, turn.cancellation);
            }
            if (cacheSafe && !listener.toolCalls.isEmpty() && !turn.cancellation.isCancelled()) {
                log.info(
                        "Cache-safe compaction in session {} called a tool, retrying without tools", scope.sessionId());
                listener.completeAll(Item.FAILED, Map.of());
                cacheSafe = false;
                listener = listener();
                routed = failover.stream(target, request(target, false, prepared), listener, turn.cancellation);
            }
            if (turn.cancellation.isCancelled()) {
                listener.completeAll(Item.CANCELLED, Map.of());
                return OptionalInt.empty();
            }
            if (listener.text().isBlank()) {
                listener.completeAll(Item.FAILED, Map.of());
                throw new LlmException("compaction_failed", "The model returned an empty summary", 0);
            }
            listener.completeAll(
                    Item.COMPLETED, Map.of("replacedItemCount", prepared.replacedItems(), "trigger", trigger));
            ledger.record(
                    scope.sessionId(),
                    scope.turnId(),
                    routed.model(),
                    routed.role(),
                    UsageLedger.COMPACTION,
                    cacheSafe ? UsageLedger.STEP : UsageLedger.COMPACTION,
                    request(routed, cacheSafe, prepared),
                    listener.usage);
            if (listener.usage != null) {
                writer.recordUsage(
                        scope.userId(),
                        scope.sessionId(),
                        scope.turnId(),
                        listener.usage.promptTokens(),
                        listener.usage.completionTokens(),
                        routed.model().cost(listener.usage),
                        model.contextWindow());
            }
            var remaining = contextAssembler.build(
                    scope.mode(),
                    globalInstructions,
                    project,
                    writer.conversation(scope.sessionId()),
                    writer.contextSnapshots(scope.sessionId()),
                    mask,
                    model.supportsVision());
            var tokens = ContextAssembler.estimateTokens(remaining);
            writer.resetContextTokens(scope.userId(), scope.sessionId(), tokens, model.contextWindow());
            return OptionalInt.of(tokens);
        }
    }

    private record Prepared(
            List<ChatMessage> legacy,
            List<ChatMessage> cacheSafe,
            List<ChatRequest.ToolDefinition> tools,
            CompactionPlanner.Estimate estimate,
            long replacedItems) {}

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
