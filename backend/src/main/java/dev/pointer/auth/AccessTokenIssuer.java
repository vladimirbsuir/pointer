package dev.pointer.auth;

import dev.pointer.common.security.AuthPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    AccessTokenIssuer(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    IssuedToken issue(UUID userId, UUID deviceId) {
        var issuedAt = clock.instant();
        var expiresAt = issuedAt.plus(properties.accessTokenTtl());
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(AuthPrincipal.DEVICE_CLAIM, deviceId.toString())
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        var token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    record IssuedToken(String value, Instant expiresAt) {}
}
