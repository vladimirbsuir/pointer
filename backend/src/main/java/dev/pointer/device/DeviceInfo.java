package dev.pointer.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record DeviceInfo(
        @NotNull UUID id,
        @NotBlank @Size(max = 100) String name,
        Map<String, Object> platform,
        @Size(max = 50) String clientVersion) {}
