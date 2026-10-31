package dev.pointer.gateway;

import dev.pointer.common.Ids;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

final class GatewayConnection {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 8 * 1024 * 1024;

    private final WebSocketSession session;
    private final JsonMapper json;
    private final UUID userId;
    private final UUID tokenDeviceId;
    private final Set<UUID> subscriptions = ConcurrentHashMap.newKeySet();
    private volatile Instant tokenExpiresAt;
    private volatile UUID deviceId;
    private volatile Set<String> capabilities = Set.of();
    private volatile Set<UUID> projects = Set.of();
    private volatile boolean ready;

    GatewayConnection(WebSocketSession session, JsonMapper json, UUID userId, UUID tokenDeviceId, Instant expiresAt) {
        this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        this.json = json;
        this.userId = userId;
        this.tokenDeviceId = tokenDeviceId;
        this.tokenExpiresAt = expiresAt;
    }

    String id() {
        return session.getId();
    }

    UUID userId() {
        return userId;
    }

    UUID tokenDeviceId() {
        return tokenDeviceId;
    }

    UUID deviceId() {
        return deviceId;
    }

    boolean isReady() {
        return ready;
    }

    boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }

    Set<UUID> projects() {
        return projects;
    }

    Instant tokenExpiresAt() {
        return tokenExpiresAt;
    }

    void refreshToken(Instant expiresAt) {
        this.tokenExpiresAt = expiresAt;
    }

    void markReady(UUID deviceId, Set<String> capabilities, Set<UUID> projects) {
        this.deviceId = deviceId;
        this.capabilities = Set.copyOf(capabilities);
        this.projects = Set.copyOf(projects);
        this.ready = true;
    }

    void updateProjects(Set<UUID> projects) {
        this.projects = Set.copyOf(projects);
    }

    void subscribe(UUID sessionId) {
        subscriptions.add(sessionId);
    }

    void unsubscribe(UUID sessionId) {
        subscriptions.remove(sessionId);
    }

    boolean isSubscribed(UUID sessionId) {
        return subscriptions.contains(sessionId);
    }

    void send(String type, String replyTo, Object payload) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("type", type);
        envelope.put("id", Ids.newId());
        envelope.put("replyTo", replyTo);
        envelope.put("payload", payload == null ? Map.of() : payload);
        sendRaw(json.writeValueAsString(envelope));
    }

    synchronized void sendRaw(String text) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(text));
        } catch (IOException | IllegalStateException exception) {
            close(CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    void close(CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException exception) {
            ready = false;
        }
    }
}
