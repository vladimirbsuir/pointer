package dev.pointer.provider;

import dev.pointer.provider.llm.Endpoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

public record ResolvedModel(
        UUID modelId,
        String modelKey,
        String displayName,
        Integer contextWindow,
        boolean supportsTools,
        BigDecimal inputPricePerMtok,
        BigDecimal outputPricePerMtok,
        Endpoint endpoint) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public BigDecimal cost(long promptTokens, long completionTokens) {
        if (inputPricePerMtok == null && outputPricePerMtok == null) {
            return null;
        }
        return price(inputPricePerMtok, promptTokens)
                .add(price(outputPricePerMtok, completionTokens))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(BigDecimal perMillion, long tokens) {
        return perMillion == null ? BigDecimal.ZERO : perMillion.multiply(BigDecimal.valueOf(tokens));
    }
}
