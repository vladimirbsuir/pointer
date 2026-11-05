package dev.pointer.gateway;

import dev.pointer.execution.DeviceChannel;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class GatewayDeviceChannel implements DeviceChannel {

    static final String EXECUTOR = "executor";

    private final ConnectionRegistry registry;

    @Override
    public boolean canExecute(UUID deviceId, UUID projectId) {
        return registry.device(deviceId)
                .filter(connection -> connection.hasCapability(EXECUTOR))
                .filter(connection -> connection.projects().contains(projectId))
                .isPresent();
    }

    @Override
    public boolean isConnected(UUID deviceId) {
        return registry.device(deviceId).isPresent();
    }

    @Override
    public Optional<UUID> executorFor(UUID userId, UUID projectId) {
        return registry.forUser(userId).stream()
                .filter(connection -> !connection.isRunner())
                .filter(connection -> connection.hasCapability(EXECUTOR))
                .filter(connection -> connection.projects().contains(projectId))
                .map(GatewayConnection::deviceId)
                .findFirst();
    }

    @Override
    public Optional<Set<String>> interpreters(UUID deviceId) {
        return registry.device(deviceId)
                .filter(connection -> connection.hasCapability(EXECUTOR))
                .map(GatewayConnection::interpreters);
    }

    @Override
    public void sendRequest(UUID deviceId, Map<String, Object> payload) {
        registry.device(deviceId).ifPresent(connection -> connection.send("exec.request", null, payload));
    }

    @Override
    public void sendCancel(UUID deviceId, UUID callId) {
        registry.device(deviceId)
                .ifPresent(connection -> connection.send("exec.cancel", null, Map.of("callId", callId)));
    }
}
