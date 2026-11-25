package dev.pointer.provider;

import dev.pointer.provider.llm.Endpoint;
import dev.pointer.provider.llm.StreamListener;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record ResolvedModel(
        UUID modelId,
        String modelKey,
        String displayName,
        Integer contextWindow,
        boolean supportsTools,
        boolean supportsVision,
        BigDecimal inputPricePerMtok,
        BigDecimal cachedInputPricePerMtok,
        BigDecimal outputPricePerMtok,
        EmbeddingPrefixes embeddingPrefixes,
        Endpoint endpoint,
        String coordinateSystem) {

    public static final String PIXELS = "pixels";
    public static final String NORMALIZED = "normalized";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public ResolvedModel(
            UUID modelId,
            String modelKey,
            String displayName,
            Integer contextWindow,
            boolean supportsTools,
            boolean supportsVision,
            BigDecimal inputPricePerMtok,
            BigDecimal cachedInputPricePerMtok,
            BigDecimal outputPricePerMtok,
            EmbeddingPrefixes embeddingPrefixes,
            Endpoint endpoint) {
        this(
                modelId,
                modelKey,
                displayName,
                contextWindow,
                supportsTools,
                supportsVision,
                inputPricePerMtok,
                cachedInputPricePerMtok,
                outputPricePerMtok,
                embeddingPrefixes,
                endpoint,
                PIXELS);
    }

    public boolean normalizedCoordinates() {
        return NORMALIZED.equals(coordinateSystem);
    }

    public BigDecimal cost(StreamListener.Usage usage) {
        return cost(usage.promptTokens(), usage.cachedPromptTokens(), usage.completionTokens());
    }

    public BigDecimal cost(long promptTokens, Long cachedPromptTokens, long completionTokens) {
        if (inputPricePerMtok == null && cachedInputPricePerMtok == null && outputPricePerMtok == null) {
            return null;
        }
        var cached = cachedPromptTokens == null ? 0 : Math.clamp(cachedPromptTokens, 0, promptTokens);
        return price(inputPricePerMtok, promptTokens - cached)
                .add(price(effectiveCachedInputPrice(), cached))
                .add(price(outputPricePerMtok, completionTokens))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    public BigDecimal effectiveCachedInputPrice() {
        return Objects.requireNonNullElse(cachedInputPricePerMtok, inputPricePerMtok);
    }

    private static BigDecimal price(BigDecimal perMillion, long tokens) {
        return perMillion == null ? BigDecimal.ZERO : perMillion.multiply(BigDecimal.valueOf(tokens));
    }
}
