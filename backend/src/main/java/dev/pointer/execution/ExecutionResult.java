package dev.pointer.execution;

import java.util.List;
import java.util.Map;

public record ExecutionResult(
        String status, Map<String, Object> result, Map<String, Object> error, List<Map<String, Object>> fileChanges) {

    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String REJECTED = "rejected";
    public static final String TIMEOUT = "timeout";
    public static final String CANCELLED = "cancelled";

    public static ExecutionResult failure(String status, String code, String message) {
        return new ExecutionResult(status, Map.of(), Map.of("code", code, "message", message), List.of());
    }

    public boolean succeeded() {
        return SUCCEEDED.equals(status);
    }
}
