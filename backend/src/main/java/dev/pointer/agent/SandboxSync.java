package dev.pointer.agent;

import dev.pointer.execution.ExecutionListener;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionResult;
import dev.pointer.execution.ExecutionService;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.transfer.TransferService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(SandboxSyncProperties.class)
class SandboxSync {

    static final String SNAPSHOT = "pointer.workspace_snapshot";
    static final String EXTRACT = "pointer.sandbox_extract";
    private static final long ARCHIVE_BYTES_PER_FILE = 1024;
    private static final long COMPRESSION_OVERHEAD_DIVISOR = 64;
    private static final int LISTED_EXCLUSIONS = 10;

    private final ExecutionService execution;
    private final TransferService transfers;
    private final SandboxSyncProperties properties;

    Preview preview(Source source) {
        var dryRun = execution.execute(
                new ExecutionRequest(
                        source.sessionId(),
                        source.projectId(),
                        source.localDeviceId(),
                        SNAPSHOT,
                        Map.of(
                                "maxFiles",
                                properties.maxFiles(),
                                "maxBytes",
                                properties.maxBytes().toBytes()),
                        true,
                        properties.timeout(),
                        source.worktree()),
                source.listener(),
                source.cancellation());
        if (!dryRun.succeeded()) {
            return new Preview(dryRun, null);
        }
        var preview = new LinkedHashMap<String, Object>();
        preview.put("kind", "sandbox_sync");
        preview.put("target", "sandbox");
        preview.put("files", dryRun.result().getOrDefault("files", 0));
        preview.put("bytes", dryRun.result().getOrDefault("bytes", 0));
        preview.put("excluded", dryRun.result().getOrDefault("excluded", List.of()));
        preview.put("excludedCount", dryRun.result().getOrDefault("excludedCount", 0));
        var lastSync = execution.lastSandboxSync(source.sessionId());
        preview.put("firstSync", lastSync.isEmpty());
        lastSync.ifPresent(sync -> preview.put("lastSyncAt", sync.syncedAt().toString()));
        return new Preview(null, preview);
    }

    Outcome run(Call call) {
        try (var transfer = transfers.open(maxArchiveBytes())) {
            var snapshot = execution.execute(
                    new ExecutionRequest(
                            call.sessionId(),
                            call.projectId(),
                            call.localDeviceId(),
                            SNAPSHOT,
                            Map.of(
                                    "uploadPath",
                                    transfer.uploadPath(),
                                    "maxFiles",
                                    properties.maxFiles(),
                                    "maxBytes",
                                    properties.maxBytes().toBytes()),
                            false,
                            properties.timeout(),
                            call.worktree()),
                    call.listener(),
                    call.cancellation());
            if (!snapshot.succeeded()) {
                return new Outcome(snapshot, null);
            }
            if (!transfer.isUploaded()) {
                return new Outcome(
                        ExecutionResult.failure(
                                ExecutionResult.FAILED,
                                "upload_failed",
                                "The project archive did not reach the Pointer server"),
                        null);
            }
            var extract = execution.execute(
                    new ExecutionRequest(
                            call.sessionId(),
                            call.projectId(),
                            call.sandboxDeviceId(),
                            EXTRACT,
                            Map.of("downloadPath", transfer.downloadPath()),
                            false,
                            properties.timeout(),
                            null),
                    call.listener(),
                    call.cancellation());
            if (!extract.succeeded()) {
                return new Outcome(extract, null);
            }
            var result = combine(snapshot.result(), extract.result(), transfer.uploadedBytes());
            execution.recordSandboxSync(call.sessionId(), number(result.get("files")), number(result.get("bytes")));
            return new Outcome(
                    new ExecutionResult(ExecutionResult.SUCCEEDED, result, null, List.of()), describe(result));
        }
    }

    private long maxArchiveBytes() {
        var contentBytes = properties.maxBytes().toBytes();
        return contentBytes
                + contentBytes / COMPRESSION_OVERHEAD_DIVISOR
                + properties.maxFiles() * ARCHIVE_BYTES_PER_FILE;
    }

    private static Map<String, Object> combine(
            Map<String, Object> snapshot, Map<String, Object> extract, long archiveBytes) {
        var result = new LinkedHashMap<String, Object>();
        result.put("files", extract.getOrDefault("files", snapshot.get("files")));
        result.put("bytes", snapshot.getOrDefault("bytes", extract.get("bytes")));
        result.put("archiveBytes", archiveBytes);
        result.put("deleted", extract.getOrDefault("deleted", 0));
        result.put("excluded", snapshot.getOrDefault("excluded", List.of()));
        result.put("excludedCount", snapshot.getOrDefault("excludedCount", 0));
        result.put("firstSync", Boolean.TRUE.equals(extract.get("firstSync")));
        result.put("workspace", extract.getOrDefault("workspace", ""));
        return result;
    }

    static String describe(Map<String, Object> result) {
        var text = new StringBuilder()
                .append("Synced ")
                .append(count(result.get("files"), "file"))
                .append(" (")
                .append(megabytes(result.get("bytes")))
                .append(") from the user's computer to ")
                .append(result.get("workspace"))
                .append(" in the sandbox.");
        var deleted = number(result.get("deleted"));
        if (deleted > 0) {
            text.append(" Deleted ")
                    .append(count(deleted, "file"))
                    .append(" that an earlier sync sent and that are gone on the user's computer.");
        }
        var excludedCount = number(result.get("excludedCount"));
        if (excludedCount > 0 && result.get("excluded") instanceof List<?> excluded) {
            text.append(" Left out ")
                    .append(count(excludedCount, "secret-looking file"))
                    .append(": ")
                    .append(String.join(
                            ", ",
                            excluded.stream()
                                    .limit(LISTED_EXCLUSIONS)
                                    .map(String::valueOf)
                                    .toList()))
                    .append(excludedCount > LISTED_EXCLUSIONS ? " and others." : ".");
        }
        if (Boolean.TRUE.equals(result.get("firstSync"))) {
            text.append(" This was the first sync of the session: the project files now have a copy in the"
                    + " sandbox container on the Pointer server, which is removed with the session.");
        } else {
            text.append(" Files the sandbox created itself were kept.");
        }
        return text.toString();
    }

    private static String count(Object value, String noun) {
        var number = number(value);
        return number + " " + noun + (number == 1 ? "" : "s");
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static String megabytes(Object bytes) {
        return String.format(Locale.ROOT, "%.1f MB", number(bytes) / (1024.0 * 1024.0));
    }

    record Source(
            UUID sessionId,
            UUID projectId,
            UUID localDeviceId,
            String worktree,
            ExecutionListener listener,
            Cancellation cancellation) {}

    record Preview(ExecutionResult failure, Map<String, Object> preview) {}

    record Call(
            UUID sessionId,
            UUID projectId,
            UUID localDeviceId,
            String worktree,
            UUID sandboxDeviceId,
            ExecutionListener listener,
            Cancellation cancellation) {}

    record Outcome(ExecutionResult result, String text) {}
}
