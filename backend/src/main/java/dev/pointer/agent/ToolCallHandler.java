package dev.pointer.agent;

import dev.pointer.agent.desktop.DesktopCalls;
import dev.pointer.customtool.CustomToolService;
import dev.pointer.customtool.ProposeTool;
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
import dev.pointer.tool.ToolErrorCodes;
import dev.pointer.tool.ToolRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
class ToolCallHandler {

    static final int MAX_RESULT_CHARS = 30_000;
    private static final String WEB_FETCH = "web_fetch";
    private static final String RUN_COMMAND = "run_command";
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
    private final CustomToolService customTools;
    private final DesktopCalls desktop;
    private final SandboxSync sandboxSync;

    void handle(TurnScope scope, int step, String providerCallId, String toolName, String argumentsJson) {
        handle(scope, null, step, providerCallId, toolName, argumentsJson);
    }

    void handleNested(
            TurnScope scope,
            UUID parentItemId,
            int step,
            String providerCallId,
            String toolName,
            String argumentsJson) {
        handle(scope, parentItemId, step, providerCallId, toolName, argumentsJson);
    }

    private void handle(
            TurnScope scope,
            UUID parentItemId,
            int step,
            String providerCallId,
            String toolName,
            String argumentsJson) {
        var arguments = parseArguments(argumentsJson);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("step", step);
        payload.put("toolName", toolName);
        payload.put("arguments", arguments == null ? Map.of() : arguments);
        payload.put("argumentsJson", argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        payload.put("providerCallId", providerCallId);
        payload.put("toolStatus", "pending");
        var item = parentItemId == null
                ? writer.createItem(scope.userId(), scope.sessionId(), scope.turnId(), Item.TOOL_CALL, payload)
                : writer.createNestedItem(
                        scope.userId(), scope.sessionId(), scope.turnId(), parentItemId, Item.TOOL_CALL, payload);
        var itemId = (UUID) item.get("id");

        if (arguments == null) {
            fail(scope, itemId, ToolErrorCodes.INVALID_ARGUMENTS, "Tool arguments are not valid JSON");
            return;
        }
        var tool = registry.find(scope.userId(), scope.projectId(), scope.deviceId(), toolName)
                .orElse(null);
        if (tool == null) {
            fail(scope, itemId, ToolErrorCodes.UNKNOWN_TOOL, "There is no tool named " + toolName);
            return;
        }
        if (parentItemId != null && !ExploreAgent.allows(tool)) {
            deny(
                    scope,
                    itemId,
                    ToolErrorCodes.TOOL_UNAVAILABLE,
                    "Tool " + toolName + " is not available to the explore sub-agent.");
            return;
        }
        if (!tool.availableIn(scope.mode())) {
            deny(
                    scope,
                    itemId,
                    ToolErrorCodes.TOOL_UNAVAILABLE,
                    "Tool " + toolName + " is not available in " + scope.mode() + " mode.");
            return;
        }
        if (tool.isDesktop() && !scope.step().vision()) {
            fail(scope, itemId, ToolErrorCodes.NEEDS_VISION, needsVisionMessage(scope.step()));
            return;
        }
        var autoApprove =
                sessionService.owned(scope.userId(), scope.sessionId()).isAutoApprove();
        var policy = permissions.evaluate(
                scope.userId(), scope.projectId(), scope.sessionId(), tool, arguments, autoApprove);
        if (PermissionService.DENY.equals(policy)) {
            deny(scope, itemId, ToolErrorCodes.BLOCKED, "This tool call is blocked by the user's permission settings.");
            return;
        }
        if (PermissionService.ASK.equals(policy) && !askForApproval(scope, itemId, tool, arguments)) {
            return;
        }
        if (tool.isCustom()) {
            writer.updateItem(
                    scope.userId(), scope.sessionId(), itemId, Map.of("customToolVersion", tool.getVersion()));
        }
        execute(scope, itemId, tool, arguments);
    }

    private static String needsVisionMessage(StepModel step) {
        var message = "Not run: the desktop tools need a model that can see the screen.";
        return step.visionFallback() == null
                ? message
                : message + " The turn continues on " + step.visionFallback() + ".";
    }

    void proposeTool(TurnScope scope, int step, String providerCallId, String argumentsJson) {
        var arguments = parseArguments(argumentsJson);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("step", step);
        payload.put("toolName", ProposeTool.NAME);
        payload.put("arguments", arguments == null ? Map.of() : arguments);
        payload.put("argumentsJson", argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        payload.put("providerCallId", providerCallId);
        payload.put("toolStatus", "pending");
        var item = writer.createItem(scope.userId(), scope.sessionId(), scope.turnId(), Item.TOOL_CALL, payload);
        var itemId = (UUID) item.get("id");
        if (arguments == null) {
            fail(scope, itemId, ToolErrorCodes.INVALID_ARGUMENTS, "Tool arguments are not valid JSON");
            return;
        }
        CustomToolService.PreparedProposal prepared;
        try {
            prepared = customTools.prepare(scope.userId(), scope.projectId(), arguments);
        } catch (CustomToolService.InvalidProposalException exception) {
            fail(scope, itemId, "invalid_proposal", exception.getMessage());
            return;
        }
        var preview = prepared.preview();
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
            return;
        }
        if (!decision.approved()) {
            var reason = decision.message() == null || decision.message().isBlank()
                    ? "The user rejected the proposed tool."
                    : "The user rejected the proposed tool with the message: " + decision.message();
            deny(scope, itemId, ToolErrorCodes.DENIED, reason);
            return;
        }
        var approved = customTools.approve(
                scope.userId(), scope.projectId(), scope.sessionId(), itemId, prepared, decision.tool());
        var result = new LinkedHashMap<String, Object>();
        result.put("toolId", approved.toolId());
        result.put("name", approved.name());
        result.put("version", approved.version());
        result.put("scope", approved.scope());
        var resultText = "The user approved " + approved.name() + " version " + approved.version() + " ("
                + approved.scope() + " scope). It is available as a tool from the next step on.";
        writer.completeItem(
                scope.userId(),
                scope.sessionId(),
                itemId,
                Item.COMPLETED,
                Map.of("toolStatus", "succeeded", "result", result, "resultText", resultText));
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
            fail(
                    scope,
                    (UUID) item.get("id"),
                    ToolErrorCodes.INVALID_ARGUMENTS,
                    "A plan needs a summary and at least one step");
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
        if (tool.isCustom()) {
            preview = new LinkedHashMap<>();
            preview.put("kind", "custom_tool");
            preview.put("name", tool.getName());
            preview.put("version", tool.getVersion());
            preview.put("interpreter", tool.getInterpreter());
            preview.put("arguments", arguments);
        } else if (tool.isMcp()) {
            preview = new LinkedHashMap<>();
            preview.put("kind", "tool");
            preview.put("server", tool.getMcpServer());
            preview.put("tool", tool.getMcpTool());
            preview.put("arguments", arguments);
        } else if (tool.isDesktop()) {
            preview = new LinkedHashMap<>();
            preview.put("kind", "desktop");
            preview.put("tool", tool.getName());
            preview.put("arguments", arguments);
        } else if (ToolDefinition.SANDBOX_SYNC.equals(tool.getOperation())) {
            var prepared = sandboxSync.preview(new SandboxSync.Source(
                    scope.sessionId(),
                    scope.projectId(),
                    scope.deviceId(),
                    scope.worktree(),
                    statusListener(scope),
                    scope.cancellation()));
            if (prepared.failure() != null) {
                complete(scope, itemId, prepared.failure(), null);
                return false;
            }
            preview = prepared.preview();
        } else if (WEB_FETCH.equals(tool.getOperation())) {
            preview = new LinkedHashMap<>();
            preview.put("kind", "fetch");
            preview.put("url", arguments.get("url"));
        } else if (ToolDefinition.WRITE.equals(tool.getKind())) {
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
            if (tool.isSandbox()) {
                preview.put("target", "sandbox");
            }
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
            deny(scope, itemId, ToolErrorCodes.DENIED, reason);
            return false;
        }
        if (decision.forSession()) {
            permissions.grantForSession(scope.sessionId(), tool, arguments);
        }
        return true;
    }

    private void executeDesktop(
            TurnScope scope, Target target, UUID itemId, ToolDefinition tool, Map<String, Object> arguments) {
        writer.updateItem(scope.userId(), scope.sessionId(), itemId, Map.of("toolStatus", "running"));
        var context = new DesktopCalls.CallContext(
                scope.sessionId(),
                scope.projectId(),
                target.deviceId(),
                target.worktree(),
                scope.cancellation(),
                scope.step().coordinateSystem());
        var operation = tool.getOperation();
        var prepared = desktop.prepare(context, operation, arguments);
        if (prepared.failure() != null) {
            complete(scope, itemId, prepared.failure(), null);
            return;
        }
        var result = execution.execute(
                new ExecutionRequest(
                        scope.sessionId(),
                        scope.projectId(),
                        target.deviceId(),
                        operation,
                        prepared.arguments(),
                        false,
                        desktop.timeout(operation, arguments),
                        target.worktree()),
                statusListener(scope),
                scope.cancellation());
        var finished = desktop.finish(context, operation, prepared, result);
        var extras = new LinkedHashMap<String, Object>();
        if (!finished.images().isEmpty()) {
            extras.put("images", finished.images());
        }
        if (!finished.marks().isEmpty()) {
            extras.put("marks", finished.marks());
        }
        complete(scope, itemId, finished.result(), null, extras, finished.text());
    }

    private void executeSync(TurnScope scope, Target sandbox, UUID itemId) {
        writer.updateItem(scope.userId(), scope.sessionId(), itemId, Map.of("toolStatus", "running"));
        var outcome = sandboxSync.run(new SandboxSync.Call(
                scope.sessionId(),
                scope.projectId(),
                scope.deviceId(),
                scope.worktree(),
                sandbox.deviceId(),
                statusListener(scope),
                scope.cancellation()));
        complete(scope, itemId, outcome.result(), null, Map.of(), outcome.text());
    }

    private void execute(TurnScope scope, UUID itemId, ToolDefinition tool, Map<String, Object> arguments) {
        var target = target(scope, tool);
        if (target.isEmpty()) {
            fail(scope, itemId, ToolErrorCodes.TOOL_UNAVAILABLE, "The sandbox is not available for this session.");
            return;
        }
        if (tool.isDesktop()) {
            executeDesktop(scope, target.get(), itemId, tool, arguments);
            return;
        }
        if (ToolDefinition.SANDBOX_SYNC.equals(tool.getOperation())) {
            executeSync(scope, target.get(), itemId);
            return;
        }
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
        var result =
                execution.execute(request(scope, target.get(), tool, arguments, false), listener, scope.cancellation());
        if (!result.fileChanges().isEmpty()) {
            fileChanges.record(scope.userId(), scope.sessionId(), scope.turnId(), itemId, result.fileChanges());
        }
        complete(scope, itemId, result, output.isEmpty() ? null : output.toString());
    }

    private void complete(TurnScope scope, UUID itemId, ExecutionResult result, String output) {
        complete(scope, itemId, result, output, Map.of(), null);
    }

    private void complete(
            TurnScope scope,
            UUID itemId,
            ExecutionResult result,
            String output,
            Map<String, Object> extras,
            String renderedText) {
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
                                "path", Objects.requireNonNullElse(change.get("path"), ""),
                                "changeKind", Objects.requireNonNullElse(change.get("changeKind"), "modified"),
                                "linesAdded", Objects.requireNonNullElse(change.get("linesAdded"), 0),
                                "linesRemoved", Objects.requireNonNullElse(change.get("linesRemoved"), 0)))
                        .toList());
        var text = result.succeeded() && renderedText != null && !renderedText.isBlank()
                ? truncate(renderedText, MAX_RESULT_CHARS)
                : resultText(result);
        updates.put("resultText", extras.containsKey("images") ? text + "\n" + DesktopCalls.SCREENSHOT_NOTE : text);
        updates.putAll(extras);
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

    private void deny(TurnScope scope, UUID itemId, String code, String reason) {
        writer.completeItem(
                scope.userId(),
                scope.sessionId(),
                itemId,
                Item.COMPLETED,
                Map.of("toolStatus", "denied", "resultText", reason, "error", Map.of("code", code, "message", reason)));
    }

    private String resultText(ExecutionResult result) {
        if (result.succeeded()) {
            return truncate(json.writeValueAsString(result.result()), MAX_RESULT_CHARS);
        }
        var error = result.error() == null ? Map.<String, Object>of() : result.error();
        return "Error (" + error.getOrDefault("code", result.status()) + "): " + error.getOrDefault("message", "");
    }

    private Optional<Target> target(TurnScope scope, ToolDefinition tool) {
        var sandboxed = tool.isSandbox()
                || (tool.isDesktop() && !execution.hasCapability(scope.deviceId(), ToolDefinition.DESKTOP_CAPABILITY));
        if (!sandboxed) {
            return Optional.of(new Target(scope.deviceId(), scope.worktree()));
        }
        return execution
                .sandboxFor(scope.userId(), scope.sessionId(), scope.projectId())
                .map(device -> new Target(device, null));
    }

    private ExecutionRequest request(
            TurnScope scope, ToolDefinition tool, Map<String, Object> arguments, boolean dryRun) {
        return request(scope, new Target(scope.deviceId(), scope.worktree()), tool, arguments, dryRun);
    }

    private ExecutionRequest request(
            TurnScope scope, Target target, ToolDefinition tool, Map<String, Object> arguments, boolean dryRun) {
        Map<String, Object> executorArguments;
        if (tool.isMcp()) {
            executorArguments =
                    Map.of("server", tool.getMcpServer(), "tool", tool.getMcpTool(), "arguments", arguments);
        } else if (tool.isCustom()) {
            executorArguments = customTools.executionArguments(tool, arguments);
        } else {
            executorArguments = arguments;
        }
        return new ExecutionRequest(
                scope.sessionId(),
                scope.projectId(),
                target.deviceId(),
                ToolDefinition.SANDBOX_RUN.equals(tool.getOperation()) ? RUN_COMMAND : tool.getOperation(),
                executorArguments,
                dryRun,
                timeoutFor(tool, arguments),
                target.worktree());
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
        if (tool.isMcp() || tool.isCustom()) {
            return DEFAULT_COMMAND_TIMEOUT;
        }
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
            String worktree,
            Cancellation cancellation,
            StepModel step) {

        TurnScope(
                UUID userId,
                UUID sessionId,
                UUID projectId,
                UUID deviceId,
                UUID turnId,
                String mode,
                String worktree,
                Cancellation cancellation) {
            this(userId, sessionId, projectId, deviceId, turnId, mode, worktree, cancellation, StepModel.NONE);
        }

        TurnScope forStep(StepModel step) {
            return new TurnScope(userId, sessionId, projectId, deviceId, turnId, mode, worktree, cancellation, step);
        }
    }

    private record Target(UUID deviceId, String worktree) {}

    record StepModel(boolean vision, String coordinateSystem, String visionFallback) {

        static final StepModel NONE = new StepModel(false, null, null);
    }
}
