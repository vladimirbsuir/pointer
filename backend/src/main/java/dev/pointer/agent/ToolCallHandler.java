package dev.pointer.agent;

import dev.pointer.execution.ExecutionListener;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionResult;
import dev.pointer.execution.ExecutionService;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.review.FileChangeService;
import dev.pointer.session.ApprovalService;
import dev.pointer.session.Item;
import dev.pointer.session.SessionEventBus;
import dev.pointer.session.SessionService;
import dev.pointer.session.SessionStatus;
import dev.pointer.session.SessionWriter;
import dev.pointer.tool.PermissionService;
import dev.pointer.tool.ToolDefinition;
import dev.pointer.tool.ToolRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class ToolCallHandler {

    static final int MAX_RESULT_CHARS = 30_000;
    private static final int MAX_STORED_OUTPUT_CHARS = 20_000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration MAX_COMMAND_TIMEOUT = Duration.ofMinutes(10);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ToolRegistry registry;
    private final PermissionService permissions;
    private final ApprovalService approvals;
    private final ExecutionService execution;
    private final FileChangeService fileChanges;
    private final SessionWriter writer;
    private final SessionService sessionService;
    private final SessionEventBus bus;
    private final JsonMapper json;

    ToolCallHandler(
            ToolRegistry registry,
            PermissionService permissions,
            ApprovalService approvals,
            ExecutionService execution,
            FileChangeService fileChanges,
            SessionWriter writer,
            SessionService sessionService,
            SessionEventBus bus,
            JsonMapper json) {
        this.registry = registry;
        this.permissions = permissions;
        this.approvals = approvals;
        this.execution = execution;
        this.fileChanges = fileChanges;
        this.writer = writer;
        this.sessionService = sessionService;
        this.bus = bus;
        this.json = json;
    }

    void handle(TurnScope scope, int step, String providerCallId, String toolName, String argumentsJson) {
        var arguments = parseArguments(argumentsJson);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("step", step);
        payload.put("toolName", toolName);
        payload.put("arguments", arguments == null ? Map.of() : arguments);
        payload.put("argumentsJson", argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        payload.put("providerCallId", providerCallId);
        payload.put("toolStatus", "pending");
        var item = writer.createItem(scope.userId(), scope.sessionId(), scope.turnId(), Item.TOOL_CALL, payload);
        var itemId = (UUID) item.get("id");

        if (arguments == null) {
            fail(scope, itemId, "invalid_arguments", "Tool arguments are not valid JSON");
            return;
        }
        var tool = registry.find(scope.userId(), scope.projectId(), toolName).orElse(null);
        if (tool == null) {
            fail(scope, itemId, "unknown_tool", "There is no tool named " + toolName);
            return;
        }
        if (!tool.availableIn(scope.mode())) {
            deny(scope, itemId, "Tool " + toolName + " is not available in " + scope.mode() + " mode.");
            return;
        }
        var autoApprove =
                sessionService.owned(scope.userId(), scope.sessionId()).isAutoApprove();
        var policy = permissions.evaluate(
                scope.userId(), scope.projectId(), scope.sessionId(), tool, arguments, autoApprove);
        if (PermissionService.DENY.equals(policy)) {
            deny(scope, itemId, "This tool call is blocked by the user's permission settings.");
            return;
        }
        if (PermissionService.ASK.equals(policy) && !askForApproval(scope, itemId, tool, arguments)) {
            return;
        }
        execute(scope, itemId, tool, arguments);
    }

    boolean proposePlan(TurnScope scope, int step, String providerCallId, String argumentsJson) {
        var arguments = parseArguments(argumentsJson);
        var steps = arguments == null ? List.<Map<String, Object>>of() : PlanTool.steps(arguments);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("step", step);
        payload.put("toolName", PlanTool.NAME);
        payload.put("argumentsJson", argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        payload.put("providerCallId", providerCallId);
        if (steps.isEmpty()) {
            payload.put("arguments", arguments == null ? Map.of() : arguments);
            payload.put("toolStatus", "pending");
            var item = writer.createItem(scope.userId(), scope.sessionId(), scope.turnId(), Item.TOOL_CALL, payload);
            fail(scope, (UUID) item.get("id"), "invalid_arguments", "A plan needs a summary and at least one step");
            return false;
        }
        payload.put("summary", PlanTool.summary(arguments));
        payload.put("steps", steps);
        payload.put("approval", SessionWriter.PLAN_PENDING);
        payload.put("resultText", PlanTool.RESULT_TEXT);
        var item = writer.createItem(scope.userId(), scope.sessionId(), scope.turnId(), Item.PLAN, payload);
        writer.completeItem(scope.userId(), scope.sessionId(), (UUID) item.get("id"), Item.COMPLETED, Map.of());
        return true;
    }

    private boolean askForApproval(TurnScope scope, UUID itemId, ToolDefinition tool, Map<String, Object> arguments) {
        Map<String, Object> preview;
        if (ToolDefinition.WRITE.equals(tool.getKind())) {
            var dryRun = execution.execute(
                    request(scope, tool, arguments, true), statusListener(scope), scope.cancellation());
            if (!dryRun.succeeded()) {
                complete(scope, itemId, dryRun, null);
                return false;
            }
            var diff = dryRun.result().get("preview");
            preview = diff instanceof Map<?, ?> map ? new LinkedHashMap<>(castMap(map)) : new LinkedHashMap<>();
            preview.put("kind", "diff");
        } else {
            preview = new LinkedHashMap<>();
            preview.put("kind", "command");
            preview.put("command", arguments.get("command"));
            preview.put("workingDirectory", arguments.getOrDefault("workingDirectory", "."));
        }
        writer.updateItem(scope.userId(), scope.sessionId(), itemId, Map.of("toolStatus", "awaiting_approval"));
        var approvalId = approvals.request(scope.userId(), scope.sessionId(), itemId, preview);
        writer.updateItem(
                scope.userId(),
                scope.sessionId(),
                itemId,
                Map.of("approvalId", approvalId, "approvalPreview", preview));
        var decision = approvals.await(scope.userId(), scope.sessionId(), approvalId, scope.cancellation());
        if (decision.cancelled()) {
            writer.completeItem(
                    scope.userId(), scope.sessionId(), itemId, Item.CANCELLED, Map.of("toolStatus", "cancelled"));
            return false;
        }
        if (!decision.approved()) {
            var reason = decision.message() == null || decision.message().isBlank()
                    ? "The user denied this tool call."
                    : "The user denied this tool call with the message: " + decision.message();
            deny(scope, itemId, reason);
            return false;
        }
        if (decision.forSession()) {
            permissions.grantForSession(scope.sessionId(), tool, arguments);
        }
        return true;
    }

    private void execute(TurnScope scope, UUID itemId, ToolDefinition tool, Map<String, Object> arguments) {
        writer.updateItem(scope.userId(), scope.sessionId(), itemId, Map.of("toolStatus", "running"));
        var output = new StringBuilder();
        var outputChars = new AtomicInteger();
        var listener = new ExecutionListener() {
            @Override
            public void onOutput(String stream, String data) {
                if (outputChars.addAndGet(data.length()) <= MAX_STORED_OUTPUT_CHARS) {
                    output.append(data);
                }
                bus.publishDelta(scope.userId(), scope.sessionId(), itemId, "output", data);
            }

            @Override
            public void onWaitingForDevice() {
                writer.changeStatus(scope.userId(), scope.sessionId(), SessionStatus.WAITING_FOR_EXECUTOR);
            }

            @Override
            public void onDeviceAvailable() {
                writer.changeStatus(scope.userId(), scope.sessionId(), SessionStatus.RUNNING);
            }
        };
        var result = execution.execute(request(scope, tool, arguments, false), listener, scope.cancellation());
        if (!result.fileChanges().isEmpty()) {
            fileChanges.record(scope.userId(), scope.sessionId(), scope.turnId(), itemId, result.fileChanges());
        }
        complete(scope, itemId, result, output.isEmpty() ? null : output.toString());
    }

    private void complete(TurnScope scope, UUID itemId, ExecutionResult result, String output) {
        var updates = new LinkedHashMap<String, Object>();
        var toolStatus = switch (result.status()) {
            case ExecutionResult.SUCCEEDED -> "succeeded";
            case ExecutionResult.CANCELLED -> "cancelled";
            default -> "failed";
        };
        updates.put("toolStatus", toolStatus);
        updates.put("result", truncateStrings(result.result()));
        if (result.error() != null) {
            updates.put("error", result.error());
        }
        if (output != null) {
            updates.put("output", output);
        }
        updates.put(
                "fileChanges",
                result.fileChanges().stream()
                        .map(change -> Map.of(
                                "path", change.getOrDefault("path", ""),
                                "changeKind", change.getOrDefault("changeKind", "modified"),
                                "linesAdded", change.getOrDefault("linesAdded", 0),
                                "linesRemoved", change.getOrDefault("linesRemoved", 0)))
                        .toList());
        updates.put("resultText", resultText(result));
        var itemStatus = switch (toolStatus) {
            case "succeeded" -> Item.COMPLETED;
            case "cancelled" -> Item.CANCELLED;
            default -> Item.FAILED;
        };
        writer.completeItem(scope.userId(), scope.sessionId(), itemId, itemStatus, updates);
    }

    private void fail(TurnScope scope, UUID itemId, String code, String message) {
        complete(scope, itemId, ExecutionResult.failure(ExecutionResult.REJECTED, code, message), null);
    }

    private void deny(TurnScope scope, UUID itemId, String reason) {
        writer.completeItem(
                scope.userId(),
                scope.sessionId(),
                itemId,
                Item.COMPLETED,
                Map.of(
                        "toolStatus",
                        "denied",
                        "resultText",
                        reason,
                        "error",
                        Map.of("code", "denied", "message", reason)));
    }

    private String resultText(ExecutionResult result) {
        if (result.succeeded()) {
            return truncate(json.writeValueAsString(result.result()), MAX_RESULT_CHARS);
        }
        var error = result.error() == null ? Map.<String, Object>of() : result.error();
        return "Error (" + error.getOrDefault("code", result.status()) + "): " + error.getOrDefault("message", "");
    }

    private ExecutionRequest request(
            TurnScope scope, ToolDefinition tool, Map<String, Object> arguments, boolean dryRun) {
        return new ExecutionRequest(
                scope.sessionId(),
                scope.projectId(),
                scope.deviceId(),
                tool.getOperation(),
                arguments,
                dryRun,
                timeoutFor(tool, arguments));
    }

    private ExecutionListener statusListener(TurnScope scope) {
        return new ExecutionListener() {
            @Override
            public void onWaitingForDevice() {
                writer.changeStatus(scope.userId(), scope.sessionId(), SessionStatus.WAITING_FOR_EXECUTOR);
            }

            @Override
            public void onDeviceAvailable() {
                writer.changeStatus(scope.userId(), scope.sessionId(), SessionStatus.RUNNING);
            }
        };
    }

    private static Duration timeoutFor(ToolDefinition tool, Map<String, Object> arguments) {
        if (!ToolDefinition.EXECUTE.equals(tool.getKind())) {
            return DEFAULT_TIMEOUT;
        }
        if (arguments.get("timeoutMs") instanceof Number millis) {
            var requested = Duration.ofMillis(millis.longValue());
            return requested.compareTo(MAX_COMMAND_TIMEOUT) > 0 ? MAX_COMMAND_TIMEOUT : requested;
        }
        return DEFAULT_COMMAND_TIMEOUT;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> truncateStrings(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put(key, truncateValue(value)));
        return result;
    }

    private static Object truncateValue(Object value) {
        if (value instanceof String text) {
            return truncate(text, MAX_RESULT_CHARS);
        }
        if (value instanceof List<?> list && list.size() > 500) {
            return list.subList(0, 500);
        }
        return value;
    }

    static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n[truncated " + (text.length() - maxChars) + " characters]";
    }

    record TurnScope(
            UUID userId,
            UUID sessionId,
            UUID projectId,
            UUID deviceId,
            UUID turnId,
            String mode,
            Cancellation cancellation) {}
}
