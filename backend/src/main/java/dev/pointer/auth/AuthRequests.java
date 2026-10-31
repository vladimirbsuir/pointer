package dev.pointer.auth;

import dev.pointer.device.DeviceInfo;
import dev.pointer.user.Locales;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class AuthRequests {

    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_LENGTH = 128;

    private AuthRequests() {}

    record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,

            @NotNull @Size(min = MIN_PASSWORD_LENGTH, max = MAX_PASSWORD_LENGTH) String password,

            @NotBlank @Size(max = 100) String displayName,
            @Pattern(regexp = Locales.PATTERN) String locale,
            @NotNull @Valid DeviceInfo device) {}

    record LoginRequest(
            @NotBlank @Email String email,
            @NotNull @Size(max = MAX_PASSWORD_LENGTH) String password,
            @NotNull @Valid DeviceInfo device) {}

    record RefreshRequest(@NotBlank String refreshToken) {}

    record LogoutRequest(@NotBlank String refreshToken) {}

    record ChangePasswordRequest(
            @Size(max = MAX_PASSWORD_LENGTH) String currentPassword,

            @NotNull @Size(min = MIN_PASSWORD_LENGTH, max = MAX_PASSWORD_LENGTH) String newPassword) {}
}
