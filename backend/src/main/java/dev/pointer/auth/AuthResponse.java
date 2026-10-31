package dev.pointer.auth;

import dev.pointer.user.MeResponse;
import java.time.Instant;

public record AuthResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        MeResponse user) {}
