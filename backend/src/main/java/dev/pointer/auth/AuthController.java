package dev.pointer.auth;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final PasswordAuthService passwordAuth;
    private final AuthSessionService sessions;

    AuthController(PasswordAuthService passwordAuth, AuthSessionService sessions) {
        this.passwordAuth = passwordAuth;
        this.sessions = sessions;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponse register(@Valid @RequestBody AuthRequests.RegisterRequest request) {
        return passwordAuth.register(request);
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody AuthRequests.LoginRequest request) {
        return passwordAuth.login(request);
    }

    @PostMapping("/refresh")
    AuthResponse refresh(@Valid @RequestBody AuthRequests.RefreshRequest request) {
        return sessions.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody AuthRequests.LogoutRequest request) {
        sessions.logout(request.refreshToken());
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(AuthPrincipal principal, @Valid @RequestBody AuthRequests.ChangePasswordRequest request) {
        passwordAuth.changePassword(principal, request);
    }
}
