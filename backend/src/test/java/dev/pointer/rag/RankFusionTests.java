package dev.pointer.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankFusionTests {

    private static final IndexStore.Candidate SESSIONS = candidate("sessions.ts", 0.9);
    private static final IndexStore.Candidate CIPHER = candidate("cipher.ts", 0.4);
    private static final IndexStore.Candidate THEME = candidate("theme.ts", 0.8);
    private static final IndexStore.Candidate BILLING = candidate("billing.ts", 0.3);

    @Test
    void chunksFoundByBothRankersComeFirst() {
        var hits = RankFusion.fuse(List.of(SESSIONS, THEME, CIPHER), List.of(CIPHER, BILLING), 4);

        assertThat(hits)
                .extracting(hit -> hit.chunk().path())
                .containsExactly("cipher.ts", "sessions.ts", "theme.ts", "billing.ts");
        assertThat(hits.getFirst().vectorRank()).isEqualTo(3);
        assertThat(hits.getFirst().lexicalRank()).isEqualTo(1);
        assertThat(hits.getFirst().score()).isEqualTo(0.4);
    }

    @Test
    void equalFusedScoresAreOrderedBySimilarity() {
        var hits = RankFusion.fuse(List.of(BILLING), List.of(THEME), 2);

        assertThat(hits).extracting(hit -> hit.chunk().path()).containsExactly("theme.ts", "billing.ts");
    }

    @Test
    void vectorOnlyKeepsTheVectorOrderAndLimit() {
        var hits = RankFusion.fuse(List.of(SESSIONS, THEME, CIPHER), List.of(), 2);

        assertThat(hits).extracting(hit -> hit.chunk().path()).containsExactly("sessions.ts", "theme.ts");
        assertThat(hits).allMatch(hit -> hit.lexicalRank() == null);
    }

    private static IndexStore.Candidate candidate(String path, double similarity) {
        return new IndexStore.Candidate(
                UUID.randomUUID(), new CodeChunk(path, null, "lines", 1, 10, "content of " + path), similarity);
    }
}
