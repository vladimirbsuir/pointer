package dev.pointer.agent.escalation;

import dev.pointer.provider.ResolvedModel;
import dev.pointer.routing.ModelRouter;
import dev.pointer.session.Item;
import dev.pointer.session.SessionWriter;
import dev.pointer.tool.ToolErrorCodes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EscalationController {

    public static final String REASON_STRUGGLE = "struggle";
    public static final String REASON_CONTEXT = "context";
    public static final String REASON_VISION = "vision";
    static final double FAST_CONTEXT_LIMIT = 0.8;
    private static final Logger log = LoggerFactory.getLogger(EscalationController.class);
    private static final String AGENT_MODE = "agent";
    private static final String PLAN_MODE = "plan";

    private final SessionWriter writer;
    private final EscalationPolicy policy;
    private final StepSignalLog stepSignals;

    EscalationController(SessionWriter writer, EscalationPolicy policy, StepSignalLog stepSignals) {
        this.writer = writer;
        this.policy = policy;
        this.stepSignals = stepSignals;
    }

    public Trajectory begin(TurnStart start) {
        var turnItems = turnItems(start.sessionId(), start.turnId());
        var startsFast = start.enabled()
                && !PLAN_MODE.equals(start.mode())
                && fastTierUsable(
                        start.routes(),
                        start.contextTokens(),
                        hasImages(turnItems) || start.contextHasVisibleScreenshots());
        return new Trajectory(
                start, startsFast ? start.routes().fast() : start.routes().step(), startsFast);
    }

    static boolean fastTierUsable(ModelRouter.ModelPlan routes, long contextTokens, boolean hasImages) {
        var fast = routes.fast().model();
        var step = routes.step().model();
        return !Objects.equals(fast.modelId(), step.modelId())
                && fast.supportsTools()
                && (!hasImages || fast.supportsVision())
                && !overFastLimit(fast, contextTokens);
    }

    private static boolean overFastLimit(ResolvedModel fast, long contextTokens) {
        return fast.contextWindow() != null && contextTokens >= fast.contextWindow() * FAST_CONTEXT_LIMIT;
    }

    private List<Map<String, Object>> turnItems(UUID sessionId, UUID turnId) {
        return writer.conversation(sessionId).stream()
                .filter(item -> Objects.equals(item.get("turnId"), turnId))
                .toList();
    }

    private static boolean calledWithoutVision(List<Map<String, Object>> turnItems, int step) {
        return turnItems.stream()
                .filter(item -> Item.TOOL_CALL.equals(item.get("type")) && item.get("parentItemId") == null)
                .filter(item -> item.get("step") instanceof Number number && number.intValue() == step)
                .anyMatch(item -> item.get("error") instanceof Map<?, ?> error
                        && ToolErrorCodes.NEEDS_VISION.equals(error.get("code")));
    }

    private static boolean hasImages(List<Map<String, Object>> turnItems) {
        return turnItems.stream()
                .filter(item -> Item.USER_MESSAGE.equals(item.get("type")))
                .map(item -> item.get("attachments"))
                .filter(attachments -> attachments instanceof List<?>)
                .flatMap(attachments -> ((List<?>) attachments).stream())
                .anyMatch(attachment -> attachment instanceof Map<?, ?> map && "image".equals(map.get("kind")));
    }

    public record TurnStart(
            UUID userId,
            UUID sessionId,
            UUID turnId,
            String mode,
            boolean enabled,
            ModelRouter.ModelPlan routes,
            long contextTokens,
            boolean contextHasVisibleScreenshots) {

        public TurnStart(
                UUID userId,
                UUID sessionId,
                UUID turnId,
                String mode,
                boolean enabled,
                ModelRouter.ModelPlan routes,
                long contextTokens) {
            this(userId, sessionId, turnId, mode, enabled, routes, contextTokens, false);
        }
    }

    public final class Trajectory {

        private final TurnStart start;
        private final List<Map<String, Integer>> history = new ArrayList<>();
        private ModelRouter.RoutedModel current;
        private boolean onFastTier;
        private boolean nudged;

        private Trajectory(TurnStart start, ModelRouter.RoutedModel current, boolean onFastTier) {
            this.start = start;
            this.current = current;
            this.onFastTier = onFastTier;
        }

        public ModelRouter.RoutedModel tier(long contextTokens) {
            if (onFastTier && overFastLimit(current.model(), contextTokens)) {
                escalate(REASON_CONTEXT, BigDecimal.ZERO, List.of());
            }
            return current;
        }

        public Optional<String> visionFallback() {
            var step = start.routes().step().model();
            return onFastTier && step.supportsVision() ? Optional.of(step.displayName()) : Optional.empty();
        }

        public void recordStep(int step, String modelLabel, boolean turnContinues) {
            try {
                var items = turnItems(start.sessionId(), start.turnId());
                var signals = StruggleSignals.compute(items, AGENT_MODE.equals(start.mode()));
                history.add(signals);
                var score = policy.score(history);
                var needsVision = turnContinues && visionFallback().isPresent() && calledWithoutVision(items, step);
                var decision = needsVision
                        ? EscalationPolicy.Decision.ESCALATE
                        : onFastTier && turnContinues
                                ? policy.decide(score, nudged)
                                : EscalationPolicy.Decision.CONTINUE;
                stepSignals.record(
                        start.sessionId(),
                        start.turnId(),
                        step,
                        modelLabel,
                        signals,
                        score,
                        decision == EscalationPolicy.Decision.ESCALATE,
                        decision == EscalationPolicy.Decision.NUDGE);
                switch (decision) {
                    case ESCALATE ->
                        escalate(
                                needsVision ? REASON_VISION : REASON_STRUGGLE,
                                score,
                                needsVision ? List.of() : EscalationPolicy.firing(history));
                    case NUDGE -> nudge(score, EscalationPolicy.firing(history));
                    case CONTINUE -> {}
                }
            } catch (RuntimeException exception) {
                log.warn("Recording the signals of step {} in session {} failed", step, start.sessionId(), exception);
            }
        }

        private void nudge(BigDecimal score, List<String> signals) {
            nudged = true;
            var notice = new LinkedHashMap<String, Object>();
            notice.put("model", current.model().displayName());
            notice.put("score", score);
            notice.put("signals", signals);
            notice.put("text", NudgeText.of(signals));
            writer.appendNotice(start.userId(), start.sessionId(), start.turnId(), Item.MODEL_NUDGED, notice);
        }

        private void escalate(String reason, BigDecimal score, List<String> signals) {
            var from = current.model();
            current = start.routes().step();
            onFastTier = false;
            var notice = new LinkedHashMap<String, Object>();
            notice.put("from", from.displayName());
            notice.put("to", current.model().displayName());
            notice.put("reason", reason);
            notice.put("score", score);
            notice.put("signals", signals);
            writer.appendNotice(start.userId(), start.sessionId(), start.turnId(), Item.MODEL_ESCALATED, notice);
        }
    }
}
