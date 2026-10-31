package dev.pointer.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SessionEventRecord(UUID sessionId, long seq, Instant occurredAt, Map<String, Object> event) {}
