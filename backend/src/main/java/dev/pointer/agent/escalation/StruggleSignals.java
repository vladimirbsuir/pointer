package dev.pointer.agent.escalation;

import dev.pointer.session.Item;
import dev.pointer.tool.ToolErrorCodes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class StruggleSignals {

    public static final String REPEAT_CALL = "repeat_call";
    public static final String ERROR_STREAK = "error_streak";
    public static final String EDIT_THRASH = "edit_thrash";
    public static final String MALFORMED_CALL = "malformed_call";
    public static final String DENIED = "denied";
    public static final String READ_ONLY_STREAK = "read_only_streak";
    public static final List<String> ALL =
            List.of(REPEAT_CALL, ERROR_STREAK, EDIT_THRASH, MALFORMED_CALL, DENIED, READ_ONLY_STREAK);
    public static final int WINDOW_STEPS = 6;

    private static final String FAILED = "failed";
    private static final String TOOL_DENIED = "denied";
    private static final Set<String> MALFORMED_CODES =
            Set.of(ToolErrorCodes.INVALID_ARGUMENTS, ToolErrorCodes.UNKNOWN_TOOL, ToolErrorCodes.TOOL_UNAVAILABLE);
    private static final Set<String> DENIED_CODES = Set.of(ToolErrorCodes.DENIED, ToolErrorCodes.BLOCKED);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private StruggleSignals() {}

    public static SequencedMap<String, Integer> compute(List<Map<String, Object>> turnItems, boolean agentMode) {
        var calls = turnItems.stream()
                .filter(item -> Item.TOOL_CALL.equals(item.get("type")) && item.get("parentItemId") == null)
                .map(Call::of)
                .toList();
        var steps = new TreeMap<Integer, List<Call>>();
        calls.forEach(call ->
                steps.computeIfAbsent(call.step(), ignored -> new ArrayList<>()).add(call));
        var window = steps.isEmpty()
                ? List.<Call>of()
                : steps.tailMap(steps.lastKey() - WINDOW_STEPS + 1).values().stream()
                        .flatMap(List::stream)
                        .toList();
        var signals = new LinkedHashMap<String, Integer>();
        signals.put(REPEAT_CALL, repeatedCalls(window));
        signals.put(ERROR_STREAK, trailingFailures(calls));
        signals.put(EDIT_THRASH, mostChangesToOnePath(window));
        signals.put(MALFORMED_CALL, (int) calls.stream()
                .filter(call -> call.hasErrorCode(MALFORMED_CODES))
                .count());
        signals.put(DENIED, (int) calls.stream().filter(Call::deniedByUser).count());
        signals.put(READ_ONLY_STREAK, agentMode ? trailingStepsWithoutChanges(steps) : 0);
        return signals;
    }

    static String normalizeArguments(String argumentsJson) {
        if (argumentsJson == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(JSON.readValue(argumentsJson, Object.class));
        } catch (JacksonException exception) {
            return argumentsJson.replaceAll("\\s+", "");
        }
    }

    private static int repeatedCalls(List<Call> window) {
        var seen = new HashSet<String>();
        var repeated = 0;
        for (var call : window) {
            if (!seen.add(call.toolName() + "\u0000" + call.arguments())) {
                repeated++;
            }
        }
        return repeated;
    }

    private static int trailingFailures(List<Call> calls) {
        var streak = 0;
        for (var index = calls.size() - 1;
                index >= 0 && FAILED.equals(calls.get(index).toolStatus());
                index--) {
            streak++;
        }
        return streak;
    }

    private static int mostChangesToOnePath(List<Call> window) {
        var changes = new HashMap<String, Integer>();
        window.forEach(call -> call.changedPaths().forEach(path -> changes.merge(path, 1, Integer::sum)));
        return changes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static int trailingStepsWithoutChanges(TreeMap<Integer, List<Call>> steps) {
        var streak = 0;
        for (var calls : steps.descendingMap().values()) {
            if (calls.stream().anyMatch(call -> !call.changedPaths().isEmpty())) {
                break;
            }
            streak++;
        }
        return streak;
    }

    private record Call(
            int step,
            String toolName,
            String arguments,
            String toolStatus,
            String errorCode,
            List<String> changedPaths) {

        static Call of(Map<String, Object> item) {
            return new Call(
                    item.get("step") instanceof Number number ? number.intValue() : 0,
                    String.valueOf(item.get("toolName")),
                    normalizeArguments((String) item.get("argumentsJson")),
                    (String) item.get("toolStatus"),
                    item.get("error") instanceof Map<?, ?> error ? (String) error.get("code") : null,
                    paths(item.get("fileChanges")));
        }

        boolean hasErrorCode(Set<String> codes) {
            return errorCode != null && codes.contains(errorCode);
        }

        boolean deniedByUser() {
            return TOOL_DENIED.equals(toolStatus) && hasErrorCode(DENIED_CODES);
        }

        private static List<String> paths(Object changes) {
            if (!(changes instanceof List<?> list)) {
                return List.of();
            }
            return list.stream()
                    .filter(change -> change instanceof Map<?, ?>)
                    .map(change -> ((Map<?, ?>) change).get("path"))
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(path -> !path.isBlank())
                    .toList();
        }
    }
}
