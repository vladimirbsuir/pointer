package dev.pointer.execution;

import java.util.Set;
import java.util.UUID;

public interface ExecutorReadiness {

    boolean manages(UUID deviceId);

    boolean isReady(ExecutionRequest request);

    void wake(ExecutionRequest request);

    default Set<String> defaultCapabilities(UUID deviceId) {
        return Set.of();
    }
}
