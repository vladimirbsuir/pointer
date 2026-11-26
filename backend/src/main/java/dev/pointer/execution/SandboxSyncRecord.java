package dev.pointer.execution;

import java.time.Instant;

public record SandboxSyncRecord(Instant syncedAt, long files, long bytes) {}
