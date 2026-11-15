package dev.pointer.provider;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/models/{modelId}/fallbacks")
@RequiredArgsConstructor
class ModelFallbackController {

    private final ProviderService service;

    @GetMapping
    ProviderDtos.FallbacksResponse list(AuthPrincipal principal, @PathVariable UUID modelId) {
        return service.fallbacks(principal.userId(), modelId);
    }

    @PutMapping
    ProviderDtos.FallbacksResponse replace(
            AuthPrincipal principal,
            @PathVariable UUID modelId,
            @Valid @RequestBody ProviderDtos.ReplaceFallbacksRequest request) {
        return service.replaceFallbacks(principal.userId(), modelId, request);
    }
}
