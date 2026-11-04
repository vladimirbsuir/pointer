package dev.pointer.auth.github;

import dev.pointer.auth.AuthResponse;
import dev.pointer.common.security.AuthPrincipal;
import dev.pointer.device.DeviceInfo;
import dev.pointer.user.Locales;
import dev.pointer.user.MeResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/github")
@EnableConfigurationProperties(GitHubProperties.class)
@Validated
@RequiredArgsConstructor
class GitHubAuthController {

    private final GitHubAuthService service;

    @GetMapping("/authorize-url")
    AuthorizeUrlResponse authorizeUrl(
            @RequestParam @NotBlank String codeChallenge,
            @RequestParam @NotBlank @Size(max = 200) String state,
            @RequestParam @NotBlank String redirectUri) {
        return new AuthorizeUrlResponse(service.authorizationUrl(codeChallenge, state, redirectUri));
    }

    @PostMapping("/callback")
    AuthResponse callback(@Valid @RequestBody GitHubCallbackRequest request) {
        return service.signIn(
                request.code(), request.codeVerifier(), request.redirectUri(), request.device(), request.locale());
    }

    @PostMapping("/link")
    MeResponse link(AuthPrincipal principal, @Valid @RequestBody GitHubLinkRequest request) {
        return service.link(principal.userId(), request.code(), request.codeVerifier(), request.redirectUri());
    }

    record AuthorizeUrlResponse(String url) {}

    record GitHubCallbackRequest(
            @NotBlank String code,
            @NotBlank String codeVerifier,
            @NotBlank String redirectUri,
            @Pattern(regexp = Locales.PATTERN) String locale,
            @NotNull @Valid DeviceInfo device) {}

    record GitHubLinkRequest(
            @NotBlank String code,
            @NotBlank String codeVerifier,
            @NotBlank String redirectUri) {}
}
