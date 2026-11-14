package dev.pointer.rag;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

final class RankFusion {

    static final int RANK_CONSTANT = 60;

    private RankFusion() {}

    static List<IndexStore.SearchHit> fuse(
            List<IndexStore.Candidate> vector, List<IndexStore.Candidate> lexical, int limit) {
        var fused = new LinkedHashMap<UUID, Fused>();
        for (var rank = 0; rank < vector.size(); rank++) {
            var candidate = vector.get(rank);
            fused.computeIfAbsent(candidate.id(), id -> new Fused(candidate)).vectorRank = rank + 1;
        }
        for (var rank = 0; rank < lexical.size(); rank++) {
            var candidate = lexical.get(rank);
            fused.computeIfAbsent(candidate.id(), id -> new Fused(candidate)).lexicalRank = rank + 1;
        }
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(Fused::score)
                        .reversed()
                        .thenComparing(Comparator.comparingDouble((Fused hit) -> hit.candidate.similarity())
                                .reversed()))
                .limit(limit)
                .map(Fused::hit)
                .toList();
    }

    private static double contribution(Integer rank) {
        return rank == null ? 0 : 1.0 / (RANK_CONSTANT + rank);
    }

    private static final class Fused {

        private final IndexStore.Candidate candidate;
        private Integer vectorRank;
        private Integer lexicalRank;

        private Fused(IndexStore.Candidate candidate) {
            this.candidate = candidate;
        }

        private double score() {
            return contribution(vectorRank) + contribution(lexicalRank);
        }

        private IndexStore.SearchHit hit() {
            return new IndexStore.SearchHit(candidate.chunk(), candidate.similarity(), vectorRank, lexicalRank);
        }
    }
}
