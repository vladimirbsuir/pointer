package dev.pointer.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import dev.pointer.provider.ResolvedModel;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompactionPlannerTests {

    private static final ResolvedModel CACHING_STEP = model("step", 100_000, "1.0", "0.1", "4.0");
    private static final ResolvedModel UNCACHED_STEP = model("step", 100_000, "1.0", null, "4.0");
    private static final ResolvedModel FAST = model("fast", 100_000, "0.3", null, "1.0");
    private static final ResolvedModel SMALL_FAST = model("small", 8_000, "0.3", null, "1.0");
    private static final ResolvedModel UNPRICED_FAST = model("unpriced", 100_000, null, null, null);

    @Test
    void theSessionModelAlwaysGetsTheCacheSafeRequest() {
        var routes = plan(CACHING_STEP, CACHING_STEP);

        var choice = CompactionPlanner.choose(routes, estimate(60_000, true, 8));

        assertThat(choice.target()).isSameAs(routes.step());
        assertThat(choice.cacheSafe()).isTrue();
        assertThat(CompactionPlanner.choose(plan(UNCACHED_STEP, UNCACHED_STEP), estimate(60_000, false, 8))
                        .cacheSafe())
                .isTrue();
    }

    @Test
    void withoutACachedPriceTheFastModelKeepsTheOldRequest() {
        var routes = plan(UNCACHED_STEP, FAST);

        var choice = CompactionPlanner.choose(routes, estimate(60_000, true, 8));

        assertThat(choice.target()).isSameAs(routes.fast());
        assertThat(choice.cacheSafe()).isFalse();
    }

    @Test
    void aWarmCacheMakesTheStepModelCheaperThanTheFastModel() {
        var routes = plan(CACHING_STEP, FAST);

        var warm = CompactionPlanner.choose(routes, estimate(60_000, true, 8));
        var cold = CompactionPlanner.choose(routes, estimate(60_000, false, 8));

        assertThat(warm.target()).isSameAs(routes.step());
        assertThat(warm.cacheSafe()).isTrue();
        assertThat(warm.cost()).isCloseTo(0.0148, offset(0.0001));
        assertThat(cold.target()).isSameAs(routes.fast());
        assertThat(cold.cacheSafe()).isFalse();
    }

    @Test
    void aFastModelTooSmallForTheConversationOrWithoutPricesIsHandledAsToday() {
        var tooSmall = plan(CACHING_STEP, SMALL_FAST);
        assertThat(CompactionPlanner.choose(tooSmall, estimate(60_000, false, 8))
                        .target())
                .isSameAs(tooSmall.step());

        var unpriced = plan(CACHING_STEP, UNPRICED_FAST);
        var choice = CompactionPlanner.choose(unpriced, estimate(60_000, true, 8));
        assertThat(choice.target()).isSameAs(unpriced.fast());
        assertThat(choice.cacheSafe()).isFalse();
        assertThat(choice.cost()).isNull();
    }

    @Test
    void theSoftTriggerNeedsACachedPriceAndAContextOfAtLeastThirtyPercent() {
        assertThat(CompactionPlanner.softTriggerApplies(UNCACHED_STEP, 90_000, 100_000))
                .isFalse();
        assertThat(CompactionPlanner.softTriggerApplies(CACHING_STEP, 29_999, 100_000))
                .isFalse();
        assertThat(CompactionPlanner.softTriggerApplies(CACHING_STEP, 30_000, 100_000))
                .isTrue();
        assertThat(CompactionPlanner.worthCompacting(
                        plan(UNCACHED_STEP, UNCACHED_STEP), estimate(60_000, true, 8), 63_000, 100_000))
                .isFalse();
    }

    @Test
    void theSoftTriggerCompactsWhenTheShorterContextPaysForTheCompaction() {
        var routes = plan(CACHING_STEP, CACHING_STEP);

        assertThat(CompactionPlanner.worthCompacting(routes, estimate(60_000, true, 8), 63_000, 100_000))
                .isTrue();
        assertThat(CompactionPlanner.worthCompacting(routes, estimate(20_000, true, 8), 33_000, 100_000))
                .isTrue();
        assertThat(CompactionPlanner.worthCompacting(routes, estimate(15_000, true, 8), 33_000, 100_000))
                .isFalse();
        assertThat(CompactionPlanner.worthCompacting(routes, estimate(60_000, true, 1), 63_000, 100_000))
                .isFalse();
    }

    @Test
    void aCarriedSummaryStaysInTheContextAndCountsAgainstTheSoftTrigger() {
        var routes = plan(CACHING_STEP, CACHING_STEP);
        var carried = new CompactionPlanner.Estimate(20_500, 23_000, 22_500, 20_000, 2_000, 8_000, 8, true);

        assertThat(CompactionPlanner.worthCompacting(routes, estimate(20_000, true, 8), 33_000, 100_000))
                .isTrue();
        assertThat(CompactionPlanner.worthCompacting(routes, carried, 33_000, 100_000))
                .isFalse();
    }

    @Test
    void afterAnIdleGapBothPathsReadTheOldContextCold() {
        var routes = plan(CACHING_STEP, CACHING_STEP);

        assertThat(CompactionPlanner.worthCompacting(routes, estimate(60_000, false, 8), 63_000, 100_000))
                .isTrue();
        assertThat(CompactionPlanner.worthCompacting(routes, estimate(15_000, false, 8), 33_000, 100_000))
                .isFalse();
        assertThat(CompactionPlanner.worthCompacting(routes, estimate(20_000, false, 2), 33_000, 100_000))
                .isFalse();
    }

    private static CompactionPlanner.Estimate estimate(int replaced, boolean warm, double callsPerTurn) {
        var cacheSafe = replaced + 3_000;
        return new CompactionPlanner.Estimate(
                replaced + 500, cacheSafe, cacheSafe - 500, replaced, 2_000, 0, callsPerTurn, warm);
    }

    private static ModelRouter.ModelPlan plan(ResolvedModel step, ResolvedModel fast) {
        var main = new ModelRouter.RoutedModel(step, ModelRouter.MAIN);
        var fastRoute = step == fast ? main : new ModelRouter.RoutedModel(fast, ModelRouter.FAST);
        return new ModelRouter.ModelPlan(main, main, fastRoute, main);
    }

    private static ResolvedModel model(String key, int window, String input, String cached, String output) {
        return new ResolvedModel(
                UUID.nameUUIDFromBytes(key.getBytes()),
                key,
                key,
                window,
                true,
                false,
                price(input),
                price(cached),
                price(output),
                null,
                null);
    }

    private static BigDecimal price(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
