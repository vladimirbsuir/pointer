package dev.pointer.execution;

import dev.pointer.provider.llm.Cancellation;
import java.util.UUID;

public interface ServerOperation {

    String operation();

    boolean isAvailable(UUID projectId);

    ExecutionResult execute(ExecutionRequest request, Cancellation cancellation);
}
