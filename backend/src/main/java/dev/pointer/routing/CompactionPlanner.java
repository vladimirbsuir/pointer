package dev.pointer.routing;

import dev.pointer.provider.ResolvedModel;
import java.math.BigDecimal;
import java.util.Objects;

public final class CompactionPlanner {

    public static final int DEFAULT_SUMMARY_TOKENS = 2_000;
    public static final double DEFAULT_CALLS_PER_TURN = 8;
    static final double SOFT_TRIGGER_MIN_RATIO = 0.3;
    private static final double MILLION = 1_000_000;

    private CompactionPlanner() {}

    public static Choice choose(ModelRouter.ModelPlan routes, Estimate estimate) {
        var step = routes.step();
        var legacy = routes.compaction(estimate.legacyPromptTokens());
        if (sameModel(legacy, step)) {
            return new Choice(step, true, cacheSafeCost(step.model(), estimate));
        }
        var legacyCost = legacyCost(legacy.model(), estimate);
        if (step.model().cachedInputPricePerMtok() == null || legacyCost == null) {
            return new Choice(legacy, false, legacyCost);
        }
        var cacheSafeCost = cacheSafeCost(step.model(), estimate);
        return cacheSafeCost < legacyCost
                ? new Choice(step, true, cacheSafeCost)
                : new Choice(legacy, false, legacyCost);
    }

    public static boolean worthCompacting(
            ModelRouter.ModelPlan routes, Estimate estimate, long contextTokens, int contextWindow) {
        var model = routes.step().model();
        if (!softTriggerApplies(model, contextTokens, contextWindow)) {
            return false;
        }
        var compaction = choose(routes, estimate).cost();
        if (compaction == null) {
            return false;
        }
        var uncached = perToken(model.inputPricePerMtok());
        var cached = perToken(model.cachedInputPricePerMtok());
        var replaced = (double) estimate.replacedTokens();
        var summary = (double) estimate.carriedSummaryTokens() + estimate.summaryTokens();
        var laterCalls = Math.max(estimate.callsPerTurn() - 1, 0);
        var firstRead = estimate.warm() ? cached : uncached;
        var withoutCompaction = replaced * firstRead + laterCalls * replaced * cached;
        var withCompaction = compaction + summary * uncached + laterCalls * summary * cached;
        return withoutCompaction > withCompaction;
    }

    public static boolean softTriggerApplies(ResolvedModel model, long contextTokens, int contextWindow) {
        return model.cachedInputPricePerMtok() != null
                && model.inputPricePerMtok() != null
                && contextTokens >= contextWindow * SOFT_TRIGGER_MIN_RATIO;
    }

    private static Double cacheSafeCost(ResolvedModel model, Estimate estimate) {
        var reusable = estimate.warm() ? estimate.reusablePrefixTokens() : 0;
        return cost(model, estimate.cacheSafePromptTokens(), reusable, estimate.summaryTokens());
    }

    private static Double legacyCost(ResolvedModel model, Estimate estimate) {
        return cost(model, estimate.legacyPromptTokens(), 0, estimate.summaryTokens());
    }

    private static Double cost(ResolvedModel model, long prompt, long cached, long completion) {
        var cost = model.cost(prompt, cached, completion);
        return cost == null ? null : cost.doubleValue();
    }

    private static boolean sameModel(ModelRouter.RoutedModel first, ModelRouter.RoutedModel second) {
        return first == second
                || Objects.equals(first.model().modelId(), second.model().modelId());
    }

    private static double perToken(BigDecimal pricePerMtok) {
        return pricePerMtok.doubleValue() / MILLION;
    }

    public record Choice(ModelRouter.RoutedModel target, boolean cacheSafe, Double cost) {}

    public record Estimate(
            int legacyPromptTokens,
            int cacheSafePromptTokens,
            int reusablePrefixTokens,
            int replacedTokens,
            int summaryTokens,
            int carriedSummaryTokens,
            double callsPerTurn,
            boolean warm) {}
}
