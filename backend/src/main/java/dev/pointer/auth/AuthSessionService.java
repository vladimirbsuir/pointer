package dev.pointer.auth;

import dev.pointer.common.Ids;
import dev.pointer.common.error.ApiException;
import dev.pointer.device.DeviceInfo;
import dev.pointer.device.DeviceService;
import dev.pointer.user.User;
import dev.pointer.user.UserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final DeviceService deviceService;
    private final UserService userService;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    AuthSessionService(
            RefreshTokenRepository refreshTokens,
            AccessTokenIssuer accessTokens,
            DeviceService deviceService,
            UserService userService,
            AuthProperties properties,
            Clock clock) {
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.deviceService = deviceService;
        this.userService = userService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public AuthResponse start(User user, DeviceInfo deviceInfo) {
        if (user.isDisabled()) {
            throw ApiException.forbidden("account_disabled", "Account is disabled");
        }
        var device = deviceService.register(user.getId(), deviceInfo);
        refreshTokens.revokeOtherUsersOnDevice(device.getId(), user.getId(), clock.instant());
        return issue(user, device.getId(), Ids.newId());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawToken) {
        var now = clock.instant();
        var token = refreshTokens
                .findByTokenHash(hash(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("invalid_refresh_token", "Refresh token is invalid"));
        if (token.isUsed()) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw ApiException.unauthorized("refresh_token_reused", "Refresh token was already used");
        }
        if (token.isExpired(now)) {
            throw ApiException.unauthorized("refresh_token_expired", "Refresh token has expired");
        }
        token.markRotated(now);
        refreshTokens.save(token);
        var user = userService.getActive(token.getUserId());
        return issue(user, token.getDeviceId(), token.getFamilyId());
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokens
                .findByTokenHash(hash(rawToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    @Transactional
    public void revokeOtherDevices(UUID userId, UUID currentDeviceId) {
        refreshTokens.revokeOtherDevices(userId, currentDeviceId, clock.instant());
    }

    private AuthResponse issue(User user, UUID deviceId, UUID familyId) {
        var now = clock.instant();
        var rawRefreshToken = newRawToken();
        var refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        refreshTokens.save(
                new RefreshToken(user.getId(), deviceId, familyId, hash(rawRefreshToken), refreshExpiresAt, now));
        var accessToken = accessTokens.issue(user.getId(), deviceId);
        return new AuthResponse(
                accessToken.value(), accessToken.expiresAt(), rawRefreshToken, refreshExpiresAt, userService.me(user));
    }

    private String newRawToken() {
        var bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
