package dev.pointer.user;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final UserService userService;

    MeController(UserService userService) {
        this.userService = userService;
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
