package dev.pointer.rag;

import dev.pointer.execution.ExecutionListener;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionService;
import dev.pointer.provider.llm.Cancellation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class DeviceIndexClient {

    private static final Duration SCAN_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CHUNKS_TIMEOUT = Duration.ofMinutes(1);

    private final ExecutionService execution;

    boolean isOnline(UUID projectId, UUID deviceId) {
        return deviceId != null && execution.canExecute(deviceId, projectId);
    }

    Map<String, String> scan(UUID projectId, UUID deviceId, Cancellation cancellation) {
        var result = run(projectId, deviceId, "pointer.index_scan", Map.of(), SCAN_TIMEOUT, cancellation);
        var hashes = new HashMap<String, String>();
        for (var file : maps(result.get("files"))) {
            hashes.put(String.valueOf(file.get("path")), String.valueOf(file.get("hash")));
        }
        return hashes;
    }

    List<FileChunks> chunks(UUID projectId, UUID deviceId, List<String> paths, Cancellation cancellation) {
        var result =
                run(projectId, deviceId, "pointer.index_chunks", Map.of("paths", paths), CHUNKS_TIMEOUT, cancellation);
        var files = new ArrayList<FileChunks>();
        for (var file : maps(result.get("files"))) {
            var path = String.valueOf(file.get("path"));
            if (Boolean.TRUE.equals(file.get("missing")) || Boolean.TRUE.equals(file.get("skipped"))) {
                files.add(new FileChunks(path, null, List.of()));
                continue;
            }
            var chunks = maps(file.get("chunks")).stream()
                    .map(chunk -> new CodeChunk(
                            path,
                            chunk.get("symbol") instanceof String symbol ? symbol : null,
                            String.valueOf(chunk.getOrDefault("kind", "lines")),
                            number(chunk.get("startLine")),
                            number(chunk.get("endLine")),
                            String.valueOf(chunk.getOrDefault("content", ""))))
                    .toList();
            files.add(new FileChunks(path, String.valueOf(file.get("hash")), chunks));
        }
        return files;
    }

    private Map<String, Object> run(
            UUID projectId,
            UUID deviceId,
            String operation,
            Map<String, Object> arguments,
            Duration timeout,
            Cancellation cancellation) {
        if (!isOnline(projectId, deviceId)) {
            throw new IndexException("executor_offline", "Open the project on this computer to index it");
        }
        var result = execution.execute(
                new ExecutionRequest(null, projectId, deviceId, operation, arguments, false, timeout, null),
                new ExecutionListener() {},
                cancellation);
        if (!result.succeeded()) {
            var error = result.error() == null ? Map.<String, Object>of() : result.error();
            throw new IndexException(
                    String.valueOf(error.getOrDefault("code", result.status())),
                    String.valueOf(error.getOrDefault("message", "The device could not read the project")));
        }
        return result.result();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(entry -> (Map<String, Object>) entry)
                .toList();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    record FileChunks(String path, String hash, List<CodeChunk> chunks) {

        boolean gone() {
            return hash == null;
        }
    }
}
