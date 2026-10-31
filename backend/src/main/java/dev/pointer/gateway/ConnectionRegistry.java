package dev.pointer.gateway;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

@Component
class ConnectionRegistry {

    static final CloseStatus REPLACED = new CloseStatus(4409, "Replaced by a newer connection");

    private final Map<String, GatewayConnection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, GatewayConnection> byDevice = new ConcurrentHashMap<>();

    void add(GatewayConnection connection) {
        connections.put(connection.id(), connection);
    }

    void registerDevice(GatewayConnection connection) {
        var previous = byDevice.put(connection.deviceId(), connection);
        if (previous != null && previous != connection) {
            previous.close(REPLACED);
        }
    }

    Optional<GatewayConnection> get(String id) {
        return Optional.ofNullable(connections.get(id));
    }

    void remove(GatewayConnection connection) {
        connections.remove(connection.id());
        if (connection.deviceId() != null) {
            byDevice.remove(connection.deviceId(), connection);
        }
    }

    List<GatewayConnection> forUser(UUID userId) {
        return connections.values().stream()
                .filter(connection ->
                        connection.isReady() && connection.userId().equals(userId))
                .toList();
    }

    Optional<GatewayConnection> device(UUID deviceId) {
        return Optional.ofNullable(deviceId == null ? null : byDevice.get(deviceId))
                .filter(GatewayConnection::isReady);
    }

    Collection<GatewayConnection> all() {
        return connections.values();
    }
}
