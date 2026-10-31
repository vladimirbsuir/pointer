package dev.pointer.auth;

import dev.pointer.common.error.ApiException;
import dev.pointer.common.security.AuthPrincipal;
import dev.pointer.user.Emails;
import dev.pointer.user.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PasswordAuthService {

    private final UserService userService;
    private final AuthSessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final String unknownUserHash;

    PasswordAuthService(UserService userService, AuthSessionService sessions, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.unknownUserHash = passwordEncoder.encode("unknown-user-password");
    }

    @Transactional
    AuthResponse register(AuthRequests.RegisterRequest request) {
        var user = userService.createWithPassword(
                Emails.normalize(request.email()),
                passwordEncoder.encode(request.password()),
                request.displayName().strip(),
                request.locale());
        return sessions.start(user, request.device());
    }

    @Transactional
    AuthResponse login(AuthRequests.LoginRequest request) {
        var user = userService.findByEmail(Emails.normalize(request.email()));
        var hash = user.flatMap(found -> found.getPasswordHash()).orElse(unknownUserHash);
        var matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !user.get().hasPassword() || !matches) {
            throw ApiException.unauthorized("invalid_credentials", "Email or password is incorrect");
        }
        return sessions.start(user.get(), request.device());
    }

    @Transactional
    void changePassword(AuthPrincipal principal, AuthRequests.ChangePasswordRequest request) {
        var user = userService.getActive(principal.userId());
        if (user.hasPassword()) {
            var currentMatches = request.currentPassword() != null
                    && passwordEncoder.matches(
                            request.currentPassword(), user.getPasswordHash().orElseThrow());
            if (!currentMatches) {
                throw ApiException.badRequest("invalid_current_password", "Current password is incorrect");
            }
        }
        userService.changePassword(user, passwordEncoder.encode(request.newPassword()));
        if (principal.deviceId() != null) {
            sessions.revokeOtherDevices(user.getId(), principal.deviceId());
        }
    }
}
