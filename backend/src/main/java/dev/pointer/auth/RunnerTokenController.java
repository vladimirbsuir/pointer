package dev.pointer.auth;

import dev.pointer.common.error.ApiException;
import dev.pointer.device.DeviceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runner")
@RequiredArgsConstructor
class RunnerTokenController {

    private final DeviceService devices;
    private final AccessTokenIssuer issuer;

    @PostMapping("/token")
    RunnerTokenResponse token(@Valid @RequestBody RunnerTokenRequest request) {
        var device = devices.authenticateRunner(request.deviceId(), request.secret())
                .orElseThrow(() -> ApiException.unauthorized("runner_unauthorized", "Unknown or revoked runner"));
        var token = issuer.issueRunner(device.getUserId(), device.getId());
        return new RunnerTokenResponse(token.value(), token.expiresAt());
    }

    record RunnerTokenRequest(
            @NotNull UUID deviceId, @NotBlank String secret) {}

    record RunnerTokenResponse(String accessToken, Instant expiresAt) {}
}
