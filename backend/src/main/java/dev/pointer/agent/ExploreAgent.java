package dev.pointer.agent;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.provider.llm.ChatRequest;
import dev.pointer.provider.llm.LlmException;
import dev.pointer.provider.llm.StreamListener;
import dev.pointer.routing.ModelRouter;
import dev.pointer.routing.RoutedClient;
import dev.pointer.session.Item;
import dev.pointer.session.SessionWriter;
import dev.pointer.session.UsageLedger;
import dev.pointer.tool.ToolDefinition;
import dev.pointer.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
class ExploreAgent {

    static final String NAME = "explore";
    static final int MAX_STEPS = 15;
    static final int MAX_REPORT_CHARS = 8_000;
    static final double MAX_CONTEXT_RATIO = 0.6;
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final Set<String> READ_OPERATIONS =
            Set.of("list_directory", "read_file", "glob", "grep", "semantic_search");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String EMPTY_REPORT = "The explore sub-agent finished without a report.";
    private static final String REPORT_REQUEST = """
            Stop investigating. Write the final report now from what you have already found, with file paths and \
            line numbers, and say what you could not check.""";

    static final ChatRequest.ToolDefinition DEFINITION = new ChatRequest.ToolDefinition(
            NAME,
            "Delegate broad reading and searching of the project to a fast read-only sub-agent. Use it to locate"
                    + " code, trace how something works across several files or survey a directory, instead of reading"
                    + " many files yourself. The sub-agent cannot change files or run commands and does not see this"
                    + " conversation, so describe the task completely. It returns a summary with file paths and line"
                    + " numbers; read the exact lines yourself before editing them.",
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "task",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "What to find out, stated narrowly: the question, the detail the sub-agent"
                                            + " needs to recognize the answer, and what the report must contain."
                                            + " Ask only for what you need next."),
                            "paths",
                            Map.of(
                                    "type",
                                    "array",
                                    "items",
                                    Map.of("type", "string"),
                                    "description",
                                    "Optional files or directories to start from, relative to the project root.")),
                    "required",
                    List.of("task")));

    private final SessionWriter writer;
    private final ToolCallHandler toolCalls;
    private final ToolRegistry registry;
    private final ContextAssembler contextAssembler;
    private final UsageLedger ledger;
    private final JsonMapper json;

    static boolean offered(ModelRouter.ModelPlan routes) {
        return routes.explore().model().supportsTools();
    }

    static boolean allows(ToolDefinition tool) {
        return ToolDefinition.READ.equals(tool.getKind())
                && !tool.isMcp()
                && !tool.isCustom()
                && READ_OPERATIONS.contains(tool.getOperation());
    }

    void run(
            ToolCallHandler.TurnScope scope,
            int step,
            String providerCallId,
            String argumentsJson,
            ModelRouter.RoutedModel explorer,
            RoutedClient.Failover failover,
            ContextAssembler.ProjectContext project) {
        var arguments = parseArguments(argumentsJson);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("step", step);
        payload.put("toolName", NAME);
        payload.put("arguments", arguments == null ? Map.of() : arguments);
        payload.put("argumentsJson", argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        payload.put("providerCallId", providerCallId);
        payload.put("toolStatus", "running");
        var item = writer.createItem(scope.userId(), scope.sessionId(), scope.turnId(), Item.TOOL_CALL, payload);
        var itemId = (UUID) item.get("id");
        var task = arguments != null && arguments.get("task") instanceof String text ? text.strip() : "";
        if (task.isEmpty()) {
            fail(scope, itemId, "invalid_arguments", "explore needs a task");
            return;
        }
        try {
            var report = investigate(scope, itemId, request(task, arguments.get("paths")), explorer, failover, project);
            if (scope.cancellation().isCancelled()) {
                writer.completeItem(
                        scope.userId(), scope.sessionId(), itemId, Item.CANCELLED, Map.of("toolStatus", "cancelled"));
                return;
            }
            writer.completeItem(
                    scope.userId(),
                    scope.sessionId(),
                    itemId,
                    Item.COMPLETED,
                    Map.of(
                            "toolStatus",
                            "succeeded",
                            "result",
                            Map.of("report", report.text(), "steps", report.steps()),
                            "resultText",
                            report.text()));
        } catch (LlmException exception) {
            if (scope.cancellation().isCancelled()) {
                writer.completeItem(
                        scope.userId(), scope.sessionId(), itemId, Item.CANCELLED, Map.of("toolStatus", "cancelled"));
                return;
            }
            fail(
                    scope,
                    itemId,
                    Objects.requireNonNullElse(exception.code(), "provider_error"),
                    "The explore sub-agent failed: "
                            + Objects.requireNonNullElse(exception.getMessage(), "the model request failed"));
        }
    }

    private Report investigate(
            ToolCallHandler.TurnScope scope,
            UUID itemId,
            String request,
            ModelRouter.RoutedModel explorer,
            RoutedClient.Failover failover,
            ContextAssembler.ProjectContext project) {
        var window = Objects.requireNonNullElse(explorer.model().contextWindow(), DEFAULT_CONTEXT_WINDOW);
        var tools = registry.available(scope.userId(), scope.projectId(), scope.deviceId()).stream()
                .filter(ExploreAgent::allows)
                .map(tool -> new ChatRequest.ToolDefinition(
                        tool.getName(), tool.getDescription(), tool.getParametersSchema()))
                .toList();
        for (var step = 0; ; step++) {
            var messages = new ArrayList<>(
                    contextAssembler.buildExplore(request, project, writer.nestedItems(scope.sessionId(), itemId)));
            var wrapUp = step >= MAX_STEPS - 1
                    || tools.isEmpty()
                    || ContextAssembler.estimateTokens(messages) > window * MAX_CONTEXT_RATIO;
            if (wrapUp && step > 0) {
                messages.add(ChatMessage.user(REPORT_REQUEST));
            }
            var reply = new Reply();
            var exploreRequest =
                    new ChatRequest(explorer.model().modelKey(), messages, wrapUp ? List.of() : tools, null);
            var used = failover.stream(explorer, exploreRequest, reply, scope.cancellation());
            recordUsage(scope, used, exploreRequest, reply.usage);
            if (scope.cancellation().isCancelled() || wrapUp || reply.calls.isEmpty()) {
                var text = reply.text.toString().strip();
                return new Report(
                        text.isEmpty() ? EMPTY_REPORT : ToolCallHandler.truncate(text, MAX_REPORT_CHARS), step + 1);
            }
            for (var call : reply.calls.values()) {
                if (scope.cancellation().isCancelled()) {
                    break;
                }
                toolCalls.handleNested(scope, itemId, step, call.id(), call.name(), call.arguments.toString());
            }
        }
    }

    private void recordUsage(
            ToolCallHandler.TurnScope scope,
            ModelRouter.RoutedModel used,
            ChatRequest request,
            StreamListener.Usage usage) {
        ledger.record(
                scope.sessionId(), scope.turnId(), used.model(), used.role(), UsageLedger.EXPLORE, request, usage);
        if (usage == null) {
            return;
        }
        writer.recordSideUsage(
                scope.userId(),
                scope.sessionId(),
                scope.turnId(),
                usage.promptTokens(),
                usage.completionTokens(),
                used.model().cost(usage));
    }

    private void fail(ToolCallHandler.TurnScope scope, UUID itemId, String code, String message) {
        writer.completeItem(
                scope.userId(),
                scope.sessionId(),
                itemId,
                Item.FAILED,
                Map.of(
                        "toolStatus",
                        "failed",
                        "error",
                        Map.of("code", code, "message", message),
                        "resultText",
                        "Error (" + code + "): " + message));
    }

    private static String request(String task, Object paths) {
        if (!(paths instanceof List<?> list) || list.isEmpty()) {
            return task;
        }
        var start = list.stream()
                .filter(String.class::isInstance)
                .map(path -> "- " + path)
                .toList();
        return start.isEmpty() ? task : task + "\n\nStart from:\n" + String.join("\n", start);
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(argumentsJson, MAP_TYPE);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private record Report(String text, int steps) {}

    private static final class Reply implements StreamListener {

        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, PendingCall> calls = new TreeMap<>();
        private Usage usage;

        @Override
        public void onText(String delta) {
            text.append(delta);
        }

        @Override
        public void onToolCallDelta(int index, String id, String name, String argumentsDelta) {
            var call = calls.computeIfAbsent(index, ignored -> new PendingCall());
            if (id != null) {
                call.id = id;
            }
            if (name != null) {
                call.name = name;
            }
            call.arguments.append(argumentsDelta);
        }

        @Override
        public void onUsage(Usage usage) {
            this.usage = usage;
        }
    }

    private static final class PendingCall {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        String id() {
            return id == null ? "call_" + UUID.randomUUID() : id;
        }

        String name() {
            return name == null ? "" : name;
        }
    }
}
