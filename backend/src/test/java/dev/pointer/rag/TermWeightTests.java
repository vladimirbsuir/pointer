package dev.pointer.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermWeightTests {

    @Test
    void rareTermsWeighMoreThanCommonOnes() {
        var rare = IndexStore.inverseDocumentFrequency(1_000, 3);
        var common = IndexStore.inverseDocumentFrequency(1_000, 400);
        var everywhere = IndexStore.inverseDocumentFrequency(1_000, 1_000);

        assertThat(rare).isGreaterThan(common);
        assertThat(common).isGreaterThan(everywhere);
        assertThat(everywhere).isPositive();
    }
}
