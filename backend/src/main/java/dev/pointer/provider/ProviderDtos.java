package dev.pointer.provider;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class ProviderDtos {

    static final int MAX_FALLBACKS = 5;
    static final String URL_PATTERN = "https?://\\S+";

    private ProviderDtos() {}

    public record ProviderResponse(
            UUID id,
            String name,
            String baseUrl,
            boolean isLocal,
            boolean hasApiKey,
            String apiKeyHint,
            List<ModelResponse> models) {}

    public record ModelResponse(
            UUID id,
            UUID providerId,
            String modelKey,
            String displayName,
            Integer contextWindow,
            Integer maxOutputTokens,
            boolean supportsTools,
            boolean supportsVision,
            BigDecimal inputPricePerMtok,
            BigDecimal cachedInputPricePerMtok,
            BigDecimal outputPricePerMtok,
            String embeddingQueryPrefix,
            String embeddingDocumentPrefix,
            String source,
            boolean enabled,
            List<UUID> fallbackModelIds) {}

    public record CreateProviderRequest(
            @NotBlank @Size(max = 100) String name,

            @NotBlank @Size(max = 500) @Pattern(regexp = URL_PATTERN) String baseUrl,

            @Size(max = 500) String apiKey,
            Boolean isLocal) {}

    public record UpdateProviderRequest(
            @Size(min = 1, max = 100) String name,
            @Size(max = 500) @Pattern(regexp = URL_PATTERN) String baseUrl,
            @Size(max = 500) String apiKey,
            Boolean clearApiKey,
            Boolean isLocal) {}

    public record CreateModelRequest(
            @NotBlank @Size(max = 200) String modelKey,
            @Size(max = 200) String displayName,
            @Min(1) @Max(100_000_000) Integer contextWindow) {}

    public record UpdateModelRequest(
            @Size(min = 1, max = 200) String displayName,
            @Min(1) @Max(100_000_000) Integer contextWindow,
            @Min(1) @Max(100_000_000) Integer maxOutputTokens,
            Boolean supportsTools,
            Boolean supportsVision,
            Boolean enabled,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal inputPricePerMtok,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal cachedInputPricePerMtok,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal outputPricePerMtok,
            Boolean clearPrices,
            @Size(max = 200) String embeddingQueryPrefix,
            @Size(max = 200) String embeddingDocumentPrefix) {}

    public record FallbacksResponse(List<UUID> modelIds) {}

    public record ReplaceFallbacksRequest(
            @NotNull @Size(max = MAX_FALLBACKS) List<@NotNull UUID> modelIds) {}

    public record ConnectionTestResponse(boolean ok, int modelCount, String errorCode, String errorMessage) {}
}
