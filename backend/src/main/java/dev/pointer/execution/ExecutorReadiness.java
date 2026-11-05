package dev.pointer.execution;

import java.util.UUID;

public interface ExecutorReadiness {

    boolean manages(UUID deviceId);

    boolean isReady(ExecutionRequest request);

    void wake(ExecutionRequest request);
}
