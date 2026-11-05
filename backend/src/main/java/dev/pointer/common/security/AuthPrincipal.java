package dev.pointer.common.security;

import java.util.UUID;

public record AuthPrincipal(UUID userId, UUID deviceId) {

    public static final String DEVICE_CLAIM = "did";
    public static final String RUNNER_CLAIM = "rnr";
}
