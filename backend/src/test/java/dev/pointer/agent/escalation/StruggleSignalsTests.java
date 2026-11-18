package dev.pointer.agent.escalation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.session.Item;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StruggleSignalsTests {

    private final List<Map<String, Object>> items = new ArrayList<>();

    @Test
    void aCleanTrajectoryFiresNothing() {
        call(0, "read_file", "{\"path\":\"a.txt\"}", "succeeded", null);
        call(1, "edit_file", "{\"path\":\"a.txt\"}", "succeeded", null, "a.txt");

        assertThat(StruggleSignals.compute(items, true))
                .containsExactly(
                        Map.entry(StruggleSignals.REPEAT_CALL, 0),
                        Map.entry(StruggleSignals.ERROR_STREAK, 0),
                        Map.entry(StruggleSignals.EDIT_THRASH, 1),
                        Map.entry(StruggleSignals.MALFORMED_CALL, 0),
                        Map.entry(StruggleSignals.DENIED, 0),
                        Map.entry(StruggleSignals.READ_ONLY_STREAK, 0),
                        Map.entry(StruggleSignals.CHECK_FAILED, 0));
    }

    @Test
    void aFollowUpAfterAFailedCheckIsAnEventOfItsFirstStep() {
        items.add(new HashMap<>(Map.of("type", Item.USER_MESSAGE, "origin", "check_failed", "text", "Fix it")));
        call(0, "read_file", "{}", "succeeded", null);

        assertThat(signal(StruggleSignals.CHECK_FAILED)).isEqualTo(1);
        var steps = List.of(Map.of(StruggleSignals.CHECK_FAILED, 1), Map.of(StruggleSignals.CHECK_FAILED, 1));
        assertThat(new EscalationPolicy(Map.of()).score(steps)).isEqualByComparingTo("0.5");
    }

    @Test
    void repeatedCallsAreFoundAfterNormalizingArgumentsWithinTheWindow() {
        call(0, "grep", "{\"pattern\":\"x\", \"path\":\"src\"}", "succeeded", null);
        call(1, "grep", "{\"path\":\"src\",\"pattern\":\"x\"}", "succeeded", null);
        assertThat(signal(StruggleSignals.REPEAT_CALL)).isEqualTo(1);

        for (var step = 2; step <= 7; step++) {
            call(step, "read_file", "{\"path\":\"f" + step + "\"}", "succeeded", null);
        }
        assertThat(signal(StruggleSignals.REPEAT_CALL)).isZero();
        assertThat(StruggleSignals.normalizeArguments("{ \"b\": 1, \"a\": {\"d\": 2, \"c\": 3} }"))
                .isEqualTo("{\"a\":{\"c\":3,\"d\":2},\"b\":1}");
        assertThat(StruggleSignals.normalizeArguments("not json  at all")).isEqualTo("notjsonatall");
    }

    @Test
    void anErrorStreakCountsOnlyTheTrailingFailures() {
        call(0, "run_command", "{\"command\":\"a\"}", "failed", "exit_code");
        call(0, "run_command", "{\"command\":\"b\"}", "succeeded", null);
        call(1, "run_command", "{\"command\":\"c\"}", "failed", "exit_code");
        call(1, "run_command", "{\"command\":\"d\"}", "failed", "exit_code");
        assertThat(signal(StruggleSignals.ERROR_STREAK)).isEqualTo(2);

        call(2, "run_command", "{\"command\":\"e\"}", "failed", "exit_code");
        assertThat(signal(StruggleSignals.ERROR_STREAK)).isEqualTo(3);
    }

    @Test
    void editThrashCountsChangesToOnePathInTheWindow() {
        call(0, "edit_file", "{\"n\":0}", "succeeded", null, "a.txt");
        call(1, "edit_file", "{\"n\":1}", "succeeded", null, "a.txt");
        call(2, "edit_file", "{\"n\":2}", "succeeded", null, "a.txt", "b.txt");

        assertThat(signal(StruggleSignals.EDIT_THRASH)).isEqualTo(3);
    }

    @Test
    void malformedCallsAndDenialsAreCountedPerTurnByErrorCode() {
        call(0, "nope", "{}", "failed", "unknown_tool");
        call(1, "read_file", "{", "failed", "invalid_arguments");
        call(2, "write_file", "{}", "denied", "tool_unavailable");
        call(3, "run_command", "{}", "denied", "denied");
        call(4, "run_command", "{\"x\":1}", "denied", "blocked");

        assertThat(signal(StruggleSignals.MALFORMED_CALL)).isEqualTo(3);
        assertThat(signal(StruggleSignals.DENIED)).isEqualTo(2);
    }

    @Test
    void theReadOnlyStreakCountsTrailingStepsWithoutChangesInAgentModeOnly() {
        call(0, "edit_file", "{}", "succeeded", null, "a.txt");
        for (var step = 1; step <= 8; step++) {
            call(step, "read_file", "{\"path\":\"f" + step + "\"}", "succeeded", null);
        }

        assertThat(signal(StruggleSignals.READ_ONLY_STREAK)).isEqualTo(8);
        assertThat(StruggleSignals.compute(items, false).get(StruggleSignals.READ_ONLY_STREAK))
                .isZero();
    }

    @Test
    void nestedExploreCallsAndOtherItemsAreIgnored() {
        call(0, "read_file", "{}", "failed", "unknown_tool");
        items.getLast().put("parentItemId", "explore-1");
        items.add(new HashMap<>(Map.of("type", Item.ASSISTANT_MESSAGE, "step", 0, "text", "thinking")));

        assertThat(StruggleSignals.compute(items, true).values()).containsOnly(0);
    }

    @Test
    void eventsCountWhenTheyHappenAndStatesForAsLongAsTheyLast() {
        var policy = new EscalationPolicy(Map.of(StruggleSignals.REPEAT_CALL, 2.0, StruggleSignals.DENIED, 1.0));
        var repeat = Map.of(StruggleSignals.REPEAT_CALL, 1);
        var repeatAgain = Map.of(StruggleSignals.REPEAT_CALL, 2);
        var quiet = Map.<String, Integer>of();
        var denied = Map.of(StruggleSignals.DENIED, 1, StruggleSignals.ERROR_STREAK, 2);
        var failing = Map.of(StruggleSignals.ERROR_STREAK, 3);
        var stillFailing = Map.of(StruggleSignals.ERROR_STREAK, 4);

        assertThat(policy.score(List.of(quiet))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(policy.score(List.of(repeat))).isEqualByComparingTo("2");
        assertThat(policy.score(List.of(repeat, quiet))).isEqualByComparingTo("1");
        assertThat(policy.score(List.of(repeat, quiet, denied))).isEqualByComparingTo("1.5");
        assertThat(policy.score(List.of(repeat, repeatAgain))).isEqualByComparingTo("3");
        assertThat(policy.score(List.of(repeat, repeat))).isEqualByComparingTo("1");
        assertThat(policy.score(List.of(failing, stillFailing))).isEqualByComparingTo("1.5");
        assertThat(EscalationPolicy.firing(List.of(repeat, repeat, failing)))
                .containsExactly(StruggleSignals.REPEAT_CALL, StruggleSignals.ERROR_STREAK);
    }

    @Test
    void theNudgePolicyWarnsOnceBelowTheEscalationThreshold() {
        var nudge = new EscalationPolicy(Map.of(), "nudge", new BigDecimal("1.5"), new BigDecimal("1.0"));
        var escalate = new EscalationPolicy(Map.of(), "escalate", new BigDecimal("1.5"), new BigDecimal("1.0"));

        assertThat(nudge.decide(new BigDecimal("0.5"), false)).isEqualTo(EscalationPolicy.Decision.CONTINUE);
        assertThat(nudge.decide(new BigDecimal("1.0"), false)).isEqualTo(EscalationPolicy.Decision.NUDGE);
        assertThat(nudge.decide(new BigDecimal("1.25"), true)).isEqualTo(EscalationPolicy.Decision.CONTINUE);
        assertThat(nudge.decide(new BigDecimal("2.5"), false)).isEqualTo(EscalationPolicy.Decision.ESCALATE);
        assertThat(escalate.decide(new BigDecimal("1.0"), false)).isEqualTo(EscalationPolicy.Decision.CONTINUE);
        assertThat(escalate.decide(new BigDecimal("1.5"), false)).isEqualTo(EscalationPolicy.Decision.ESCALATE);
    }

    private int signal(String name) {
        return StruggleSignals.compute(items, true).get(name);
    }

    private void call(int step, String tool, String arguments, String status, String errorCode, String... changed) {
        var item = new HashMap<String, Object>();
        item.put("type", Item.TOOL_CALL);
        item.put("step", step);
        item.put("toolName", tool);
        item.put("argumentsJson", arguments);
        item.put("toolStatus", status);
        if (errorCode != null) {
            item.put("error", Map.of("code", errorCode, "message", "error"));
        }
        item.put(
                "fileChanges",
                List.of(changed).stream().map(path -> Map.of("path", path)).toList());
        items.add(item);
    }
}
