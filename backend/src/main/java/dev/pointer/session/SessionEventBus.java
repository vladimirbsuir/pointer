package dev.pointer.session;

import java.util.Map;
import java.util.UUID;

public interface SessionEventBus {

    void publishEvent(UUID userId, SessionEventRecord event);

    void publishDelta(UUID userId, UUID sessionId, UUID itemId, String field, String delta);

    void publishSessionsChanged(UUID userId, Map<String, Object> change);
}
