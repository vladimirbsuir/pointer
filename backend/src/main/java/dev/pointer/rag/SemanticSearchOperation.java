package dev.pointer.rag;

import dev.pointer.common.error.ApiException;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionResult;
import dev.pointer.execution.ServerOperation;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.LlmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class SemanticSearchOperation implements ServerOperation {

    static final String OPERATION = "semantic_search";
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;

    private final IndexService index;

    @Override
    public String operation() {
        return OPERATION;
    }

    @Override
    public boolean isAvailable(UUID projectId) {
        return index.isSearchable(projectId);
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request, Cancellation cancellation) {
        var arguments = request.arguments();
        if (!(arguments.get("query") instanceof String query) || query.isBlank()) {
            return ExecutionResult.failure(ExecutionResult.REJECTED, "invalid_arguments", "Missing argument query");
        }
        try {
            var outcome = index.search(
                    request.projectId(),
                    request.deviceId(),
                    query.strip(),
                    pathPrefix(arguments.get("path")),
                    limit(arguments.get("limit")),
                    cancellation);
            var result = new LinkedHashMap<String, Object>();
            result.put(
                    "results",
                    outcome.hits().stream().map(SemanticSearchOperation::hit).toList());
            result.put("index", outcome.freshness());
            return new ExecutionResult(ExecutionResult.SUCCEEDED, result, null, List.of());
        } catch (IndexException exception) {
            return ExecutionResult.failure(ExecutionResult.FAILED, exception.code(), exception.getMessage());
        } catch (LlmException exception) {
            return ExecutionResult.failure(ExecutionResult.FAILED, "embedding_failed", exception.getMessage());
        } catch (ApiException exception) {
            return ExecutionResult.failure(ExecutionResult.FAILED, exception.code(), exception.getMessage());
        }
    }

    private static Map<String, Object> hit(IndexStore.SearchHit hit) {
        var chunk = hit.chunk();
        var result = new LinkedHashMap<String, Object>();
        result.put("path", chunk.path());
        result.put("startLine", chunk.startLine());
        result.put("endLine", chunk.endLine());
        if (chunk.symbol() != null) {
            result.put("symbol", chunk.symbol());
        }
        result.put("kind", chunk.kind());
        result.put("score", Math.round(hit.score() * 1000) / 1000.0);
        result.put("content", chunk.content());
        return result;
    }

    private static int limit(Object value) {
        if (!(value instanceof Number number)) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(number.intValue(), 1, MAX_LIMIT);
    }

    static String pathPrefix(Object value) {
        if (!(value instanceof String path)) {
            return "";
        }
        var normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./") || normalized.startsWith("/")) {
            normalized = normalized.substring(normalized.startsWith("./") ? 2 : 1);
        }
        if (normalized.isEmpty() || normalized.equals(".")) {
            return "";
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }
}
