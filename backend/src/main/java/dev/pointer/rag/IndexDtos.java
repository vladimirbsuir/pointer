package dev.pointer.rag;

import java.time.Instant;
import java.util.UUID;

public final class IndexDtos {

    private IndexDtos() {}

    public record IndexStatusResponse(
            String status,
            UUID embeddingModelId,
            String embeddingModelKey,
            int filesTotal,
            int filesDone,
            int fileCount,
            int chunkCount,
            String error,
            Instant indexedAt,
            boolean prefixesChanged) {

        static final String NONE = "none";

        static IndexStatusResponse of(IndexState state, boolean prefixesChanged) {
            if (state == null) {
                return new IndexStatusResponse(NONE, null, null, 0, 0, 0, 0, null, null, false);
            }
            return new IndexStatusResponse(
                    state.status(),
                    state.embeddingModelId(),
                    state.embeddingModelKey(),
                    state.filesTotal(),
                    state.filesDone(),
                    state.fileCount(),
                    state.chunkCount(),
                    state.error(),
                    state.indexedAt(),
                    prefixesChanged);
        }
    }

    public record StartIndexRequest(Boolean rebuild) {}
}
