package dev.pointer.execution;

import java.util.Map;
import java.util.UUID;

public interface DeviceChannel {

    boolean canExecute(UUID deviceId, UUID projectId);

    boolean isConnected(UUID deviceId);

    void sendRequest(UUID deviceId, Map<String, Object> payload);

    void sendCancel(UUID deviceId, UUID callId);
}
