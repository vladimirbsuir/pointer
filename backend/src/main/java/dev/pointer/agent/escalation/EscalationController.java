package dev.pointer.agent.escalation;

import dev.pointer.session.SessionWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EscalationController {

    private static final Logger log = LoggerFactory.getLogger(EscalationController.class);
    private static final String AGENT_MODE = "agent";

    private final SessionWriter writer;
    private final EscalationPolicy policy;
    private final StepSignalLog stepSignals;

    public Trajectory begin(UUID sessionId, UUID turnId, String mode) {
        return new Trajectory(sessionId, turnId, AGENT_MODE.equals(mode));
    }

    public final class Trajectory {

        private final UUID sessionId;
        private final UUID turnId;
        private final boolean agentMode;
        private final List<Map<String, Integer>> history = new ArrayList<>();

        private Trajectory(UUID sessionId, UUID turnId, boolean agentMode) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.agentMode = agentMode;
        }

        public void recordStep(int step, String modelLabel) {
            try {
                var turnItems = writer.conversation(sessionId).stream()
                        .filter(item -> Objects.equals(item.get("turnId"), turnId))
                        .toList();
                var signals = StruggleSignals.compute(turnItems, agentMode);
                history.add(signals);
                stepSignals.record(sessionId, turnId, step, modelLabel, signals, policy.score(history), false);
            } catch (RuntimeException exception) {
                log.warn("Recording the signals of step {} in session {} failed", step, sessionId, exception);
            }
        }
    }
}
