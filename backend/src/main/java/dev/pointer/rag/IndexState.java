package dev.pointer.rag;

import java.time.Instant;
import java.util.UUID;

record IndexState(
        UUID projectId,
        UUID userId,
        UUID embeddingModelId,
        String embeddingModelKey,
        Integer dimensions,
        String status,
        int filesTotal,
        int filesDone,
        int fileCount,
        int chunkCount,
        String error,
        Instant indexedAt) {

    static final String INDEXING = "indexing";
    static final String READY = "ready";
    static final String FAILED = "failed";
}
