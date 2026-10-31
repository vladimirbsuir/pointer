package dev.pointer.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pointer.auth")
record AuthProperties(
        String jwtSecret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        List<String> allowedOrigins) {}
