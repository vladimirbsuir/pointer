package dev.pointer.user;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class MeController {

    private final UserService userService;
    private final SettingsService settingsService;

    @GetMapping("/settings")
    SettingsService.SettingsResponse settings(AuthPrincipal principal) {
        return settingsService.get(principal.userId());
    }

    @PutMapping("/settings")
    SettingsService.SettingsResponse updateSettings(
            AuthPrincipal principal, @Valid @RequestBody SettingsService.UpdateSettingsRequest request) {
        return settingsService.update(principal.userId(), request);
    }

    @GetMapping
    MeResponse me(AuthPrincipal principal) {
        return userService.me(principal.userId());
    }

    @PatchMapping
    MeResponse update(AuthPrincipal principal, @Valid @RequestBody UpdateMeRequest request) {
        return userService.update(principal.userId(), request);
    }
}
