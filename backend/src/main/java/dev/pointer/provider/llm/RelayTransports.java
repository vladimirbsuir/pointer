package dev.pointer.provider.llm;

import java.util.UUID;

public interface RelayTransports {

    HttpTransport forUser(UUID userId, UUID preferredDeviceId);
}
