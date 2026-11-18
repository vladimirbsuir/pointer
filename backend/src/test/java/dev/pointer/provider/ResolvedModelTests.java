package dev.pointer.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.llm.Endpoint;
import dev.pointer.provider.llm.StreamListener;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResolvedModelTests {

    @Test
    void billsCachedPromptTokensAtTheCachedPrice() {
        var model = model("2", "0.5", "8");

        assertThat(model.cost(new StreamListener.Usage(1_000_000, 500_000, 600_000L)))
                .isEqualByComparingTo("5.1");
    }

    @Test
    void billsCachedPromptTokensAtTheInputPriceWithoutACachedPrice() {
        var model = model("2", null, "8");

        assertThat(model.cost(new StreamListener.Usage(1_000_000, 500_000, 600_000L)))
                .isEqualByComparingTo("6");
        assertThat(model.cost(new StreamListener.Usage(1_000_000, 500_000))).isEqualByComparingTo("6");
    }

    @Test
    void ignoresTheCachedPriceWhenTheProviderReportsNoCaching() {
        var model = model("2", "0.5", "8");

        assertThat(model.cost(new StreamListener.Usage(1_000_000, 500_000))).isEqualByComparingTo("6");
    }

    @Test
    void clampsCachedTokensToThePrompt() {
        var model = model("2", "0.5", "8");

        assertThat(model.cost(new StreamListener.Usage(1_000_000, 0, 2_000_000L)))
                .isEqualByComparingTo("0.5");
    }

    @Test
    void hasNoCostWithoutAnyPrice() {
        assertThat(model(null, null, null).cost(new StreamListener.Usage(10, 10, 5L)))
                .isNull();
    }

    private static ResolvedModel model(String input, String cached, String output) {
        return new ResolvedModel(
                UUID.randomUUID(),
                "model",
                "Model",
                null,
                true,
                false,
                input == null ? null : new BigDecimal(input),
                cached == null ? null : new BigDecimal(cached),
                output == null ? null : new BigDecimal(output),
                EmbeddingPrefixes.NONE,
                new Endpoint("http://model.test/v1", null, null));
    }
}
