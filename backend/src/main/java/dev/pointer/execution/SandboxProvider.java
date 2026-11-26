package dev.pointer.execution;

import java.util.Optional;
import java.util.UUID;

public interface SandboxProvider {

    boolean offers(UUID userId, UUID projectId, UUID deviceId);

    Optional<UUID> sandboxFor(UUID userId, UUID sessionId, UUID projectId);

    Optional<SandboxSyncRecord> lastSync(UUID sessionId);

    void recordSync(UUID sessionId, long files, long bytes);
}
