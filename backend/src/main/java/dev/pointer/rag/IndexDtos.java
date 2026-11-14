package dev.pointer.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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
            boolean prefixesChanged,
            String searchStrategy) {

        static final String NONE = "none";

        static IndexStatusResponse of(IndexState state, boolean prefixesChanged) {
            if (state == null) {
                return new IndexStatusResponse(
                        NONE, null, null, 0, 0, 0, 0, null, null, false, SearchStrategy.HYBRID.wireName());
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
                    prefixesChanged,
                    state.searchStrategy().wireName());
        }
    }

    public record StartIndexRequest(Boolean rebuild) {}

    public record IndexQueryRequest(
            @NotBlank @Size(max = 2_000) String query,
            @Min(1) @Max(50) Integer limit,
            @Pattern(regexp = "vector|hybrid|lexical") String strategy,
            @Size(max = 500) String path) {}

    public record IndexQueryResponse(String strategy, long tookMs, List<IndexQueryHit> results) {}

    public record IndexQueryHit(
            String path,
            String symbol,
            String kind,
            int startLine,
            int endLine,
            double score,
            Integer vectorRank,
            Integer lexicalRank) {}

    public record IndexSettingsRequest(
            @NotNull @Pattern(regexp = "vector|hybrid") String searchStrategy) {}
}
