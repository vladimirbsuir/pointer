package dev.pointer.provider;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
class ProviderController {

    private final ProviderService service;

    @GetMapping
    List<ProviderDtos.ProviderResponse> list(AuthPrincipal principal) {
        return service.list(principal.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProviderDtos.ProviderResponse create(
            AuthPrincipal principal, @Valid @RequestBody ProviderDtos.CreateProviderRequest request) {
        return service.create(principal.userId(), request);
    }

    @PatchMapping("/{providerId}")
    ProviderDtos.ProviderResponse update(
            AuthPrincipal principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody ProviderDtos.UpdateProviderRequest request) {
        return service.update(principal.userId(), providerId, request);
    }

    @DeleteMapping("/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthPrincipal principal, @PathVariable UUID providerId) {
        service.delete(principal.userId(), providerId);
    }

    @PostMapping("/{providerId}/test")
    ProviderDtos.ConnectionTestResponse test(AuthPrincipal principal, @PathVariable UUID providerId) {
        return service.test(principal.userId(), providerId, principal.deviceId());
    }

    @PostMapping("/{providerId}/models/refresh")
    ProviderDtos.ProviderResponse refreshModels(AuthPrincipal principal, @PathVariable UUID providerId) {
        return service.refreshModels(principal.userId(), providerId, principal.deviceId());
    }

    @PostMapping("/{providerId}/models")
    @ResponseStatus(HttpStatus.CREATED)
    ProviderDtos.ModelResponse addModel(
            AuthPrincipal principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody ProviderDtos.CreateModelRequest request) {
        return service.addModel(principal.userId(), providerId, request);
    }

    @PatchMapping("/{providerId}/models/{modelId}")
    ProviderDtos.ModelResponse updateModel(
            AuthPrincipal principal,
            @PathVariable UUID providerId,
            @PathVariable UUID modelId,
            @Valid @RequestBody ProviderDtos.UpdateModelRequest request) {
        return service.updateModel(principal.userId(), providerId, modelId, request);
    }

    @DeleteMapping("/{providerId}/models/{modelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteModel(AuthPrincipal principal, @PathVariable UUID providerId, @PathVariable UUID modelId) {
        service.deleteModel(principal.userId(), providerId, modelId);
    }
}
