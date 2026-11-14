package dev.pointer.rag;

import dev.pointer.provider.EmbeddingPrefixes;
import java.time.Instant;
import java.util.UUID;

record IndexState(
        UUID projectId,
        UUID userId,
        UUID embeddingModelId,
        String embeddingModelKey,
        EmbeddingPrefixes prefixes,
        Integer dimensions,
        String status,
        int filesTotal,
        int filesDone,
        int fileCount,
        int chunkCount,
        String error,
        Instant indexedAt,
        int lexicalVersion,
        SearchStrategy searchStrategy) {

    static final String INDEXING = "indexing";
    static final String READY = "ready";
    static final String FAILED = "failed";
}
