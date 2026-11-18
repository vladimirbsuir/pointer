package dev.pointer.agent.escalation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
            StruggleSignals.READ_ONLY_STREAK, 8);
    private static final double DEFAULT_WEIGHT = 1.0;
    private static final double DECAY_PER_STEP = 0.5;

    private final Map<String, Double> weights;

    @Autowired
    public EscalationPolicy(Environment environment) {
        this(StruggleSignals.ALL.stream()
                .collect(Collectors.toMap(
                        signal -> signal,
                        signal -> environment.getProperty(
                                "pointer.escalation.weight." + signal, Double.class, DEFAULT_WEIGHT))));
    }

    EscalationPolicy(Map<String, Double> weights) {
        this.weights = Map.copyOf(weights);
    }

    static boolean fires(String signal, int value) {
        return value >= FIRING_THRESHOLDS.getOrDefault(signal, Integer.MAX_VALUE);
    }

    public BigDecimal score(List<Map<String, Integer>> stepSignals) {
        var score = 0.0;
        for (var signal : StruggleSignals.ALL) {
            for (var stepsAgo = 0; stepsAgo < stepSignals.size(); stepsAgo++) {
                var signals = stepSignals.get(stepSignals.size() - 1 - stepsAgo);
                if (fires(signal, signals.getOrDefault(signal, 0))) {
                    score += weights.getOrDefault(signal, DEFAULT_WEIGHT) * Math.pow(DECAY_PER_STEP, stepsAgo);
                    break;
                }
            }
        }
        return BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP);
    }
}
