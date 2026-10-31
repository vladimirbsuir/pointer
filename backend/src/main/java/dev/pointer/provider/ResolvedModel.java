package dev.pointer.provider;

import dev.pointer.provider.llm.Endpoint;
import java.util.UUID;

public record ResolvedModel(
        UUID modelId,
        String modelKey,
        String displayName,
        Integer contextWindow,
        boolean supportsTools,
        Endpoint endpoint) {}
