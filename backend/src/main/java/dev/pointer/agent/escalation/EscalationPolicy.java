package dev.pointer.agent.escalation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class EscalationPolicy {

    static final Map<String, Integer> FIRING_THRESHOLDS = Map.of(
            StruggleSignals.REPEAT_CALL, 1,
            StruggleSignals.ERROR_STREAK, 3,
            StruggleSignals.EDIT_THRASH, 3,
            StruggleSignals.MALFORMED_CALL, 1,
            StruggleSignals.DENIED, 1,
            StruggleSignals.READ_ONLY_STREAK, 8,
            StruggleSignals.CHECK_FAILED, 1);
    public static final String ESCALATE = "escalate";
    public static final String NUDGE = "nudge";
    private static final double DEFAULT_WEIGHT = 1.0;
    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("1.5");
    private static final BigDecimal DEFAULT_NUDGE_THRESHOLD = new BigDecimal("1.0");
    private static final double DECAY_PER_STEP = 0.5;

    private final Map<String, Double> weights;
    private final String kind;
    private final BigDecimal threshold;
    private final BigDecimal nudgeThreshold;

    @Autowired
    public EscalationPolicy(Environment environment) {
        this(
                StruggleSignals.ALL.stream()
                        .collect(Collectors.toMap(
                                signal -> signal,
                                signal -> environment.getProperty(
                                        "pointer.escalation.weight." + signal, Double.class, DEFAULT_WEIGHT))),
                environment.getProperty("pointer.escalation.policy", ESCALATE),
                environment.getProperty("pointer.escalation.threshold", BigDecimal.class, DEFAULT_THRESHOLD),
                environment.getProperty(
                        "pointer.escalation.nudge-threshold", BigDecimal.class, DEFAULT_NUDGE_THRESHOLD));
    }

    EscalationPolicy(Map<String, Double> weights) {
        this(weights, ESCALATE, DEFAULT_THRESHOLD, DEFAULT_NUDGE_THRESHOLD);
    }

    EscalationPolicy(Map<String, Double> weights, String kind, BigDecimal threshold, BigDecimal nudgeThreshold) {
        if (!ESCALATE.equals(kind) && !NUDGE.equals(kind)) {
            throw new IllegalArgumentException("pointer.escalation.policy must be escalate or nudge");
        }
        this.weights = Map.copyOf(weights);
        this.kind = kind;
        this.threshold = threshold;
        this.nudgeThreshold = nudgeThreshold;
    }

    Decision decide(BigDecimal score, boolean alreadyNudged) {
        if (score.compareTo(threshold) >= 0) {
            return Decision.ESCALATE;
        }
        if (NUDGE.equals(kind) && !alreadyNudged && score.compareTo(nudgeThreshold) >= 0) {
            return Decision.NUDGE;
        }
        return Decision.CONTINUE;
    }

    enum Decision {
        CONTINUE,
        NUDGE,
        ESCALATE
    }

    static boolean fires(String signal, int value, int previousValue) {
        var reached = value >= FIRING_THRESHOLDS.getOrDefault(signal, Integer.MAX_VALUE);
        return StruggleSignals.EVENTS.contains(signal) ? reached && value > previousValue : reached;
    }

    static boolean firesAt(String signal, List<Map<String, Integer>> stepSignals, int index) {
        var previous = index == 0 ? 0 : stepSignals.get(index - 1).getOrDefault(signal, 0);
        return fires(signal, stepSignals.get(index).getOrDefault(signal, 0), previous);
    }

    static List<String> firing(List<Map<String, Integer>> stepSignals) {
        return StruggleSignals.ALL.stream()
                .filter(signal ->
                        IntStream.range(0, stepSignals.size()).anyMatch(index -> firesAt(signal, stepSignals, index)))
                .toList();
    }

    public BigDecimal score(List<Map<String, Integer>> stepSignals) {
        var score = 0.0;
        var last = stepSignals.size() - 1;
        for (var signal : StruggleSignals.ALL) {
            for (var index = 0; index <= last; index++) {
                if (firesAt(signal, stepSignals, index)) {
                    score += weights.getOrDefault(signal, DEFAULT_WEIGHT) * Math.pow(DECAY_PER_STEP, last - index);
                }
            }
        }
        return BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP);
    }
}
