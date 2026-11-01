package dev.pointer.execution;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public record ExecutionRequest(
        UUID sessionId,
        UUID projectId,
        UUID deviceId,
        String operation,
        Map<String, Object> arguments,
        boolean dryRun,
        Duration timeout) {}
