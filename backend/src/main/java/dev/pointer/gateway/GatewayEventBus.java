package dev.pointer.gateway;

import dev.pointer.session.SessionEventBus;
import dev.pointer.session.SessionEventRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class GatewayEventBus implements SessionEventBus {

    static final String VIEWER = "viewer";

    private final ConnectionRegistry registry;

    GatewayEventBus(ConnectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void publishEvent(UUID userId, SessionEventRecord event) {
        var payload = eventPayload(event);
        registry.forUser(userId).stream()
                .filter(connection -> connection.isSubscribed(event.sessionId()))
                .forEach(connection -> connection.send("session.event", null, payload));
    }

    @Override
    public void publishDelta(UUID userId, UUID sessionId, UUID itemId, String field, String delta) {
        var payload = Map.of("sessionId", sessionId, "itemId", itemId, "field", field, "delta", delta);
        registry.forUser(userId).stream()
                .filter(connection -> connection.isSubscribed(sessionId))
                .forEach(connection -> connection.send("session.delta", null, payload));
    }

    @Override
    public void publishSessionsChanged(UUID userId, Map<String, Object> change) {
        registry.forUser(userId).stream()
                .filter(connection -> connection.hasCapability(VIEWER))
                .forEach(connection -> connection.send("sessions.changed", null, change));
    }

    static Map<String, Object> eventPayload(SessionEventRecord event) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", event.sessionId());
        payload.put("seq", event.seq());
        payload.put("occurredAt", event.occurredAt());
        payload.put("event", event.event());
        return payload;
    }
}
