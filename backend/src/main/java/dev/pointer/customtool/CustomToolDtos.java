package dev.pointer.customtool;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CustomToolDtos {

    private CustomToolDtos() {}

    record CustomToolResponse(
            UUID id,
            String name,
            String description,
            String scope,
            UUID projectId,
            String interpreter,
            Map<String, Object> parameters,
            int version,
            String defaultPolicy,
            List<String> modes,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {}

    record VersionResponse(
            int version,
            String description,
            Map<String, Object> parameters,
            String interpreter,
            String body,
            UUID sessionId,
            Instant createdAt,
            boolean current) {}

    record UpdateCustomToolRequest(
            @Pattern(regexp = "allow|ask|deny") String defaultPolicy,
            @Size(max = 3) List<@Pattern(regexp = "ask|plan|agent") String> modes,
            Boolean enabled) {}
}
