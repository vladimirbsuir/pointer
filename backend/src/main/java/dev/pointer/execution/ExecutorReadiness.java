package dev.pointer.execution;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ExecutorReadiness {

    boolean manages(UUID deviceId);

    boolean isReady(ExecutionRequest request);

    void wake(ExecutionRequest request);

    default Optional<String> failure(ExecutionRequest request) {
        return Optional.empty();
    }

    default Set<String> defaultCapabilities(UUID deviceId) {
        return Set.of();
    }
}
