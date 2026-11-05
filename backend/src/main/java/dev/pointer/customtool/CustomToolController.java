package dev.pointer.customtool;

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
@RequestMapping("/api/v1/custom-tools")
@RequiredArgsConstructor
class CustomToolController {

    private final CustomToolService service;

    @GetMapping
    List<CustomToolDtos.CustomToolResponse> list(AuthPrincipal principal) {
        return service.list(principal.userId());
    }

    @GetMapping("/{toolId}/versions")
    List<CustomToolDtos.VersionResponse> versions(AuthPrincipal principal, @PathVariable UUID toolId) {
        return service.versions(principal.userId(), toolId);
    }

    @PatchMapping("/{toolId}")
    CustomToolDtos.CustomToolResponse update(
            AuthPrincipal principal,
            @PathVariable UUID toolId,
            @Valid @RequestBody CustomToolDtos.UpdateCustomToolRequest request) {
        return service.update(principal.userId(), toolId, request);
    }

    @PostMapping("/{toolId}/versions/{version}/restore")
    CustomToolDtos.CustomToolResponse restore(
            AuthPrincipal principal, @PathVariable UUID toolId, @PathVariable int version) {
        return service.restore(principal.userId(), toolId, version);
    }

    @DeleteMapping("/{toolId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthPrincipal principal, @PathVariable UUID toolId) {
        service.delete(principal.userId(), toolId);
    }
}
