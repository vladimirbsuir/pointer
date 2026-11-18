package dev.pointer.agent;

import dev.pointer.session.AgentSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ContextPolicy {

    static final double COMPACTION_RATIO = 0.8;
    static final double MASKING_SAFETY_RATIO = 0.95;

    private final MaskPolicy masking;

    ContextPolicy(
            @Value("${pointer.context.keep-results:10}") int keepResults,
            @Value("${pointer.context.mask-block:10}") int maskBlock) {
        this.masking = new MaskPolicy(keepResults, maskBlock);
    }

    MaskPolicy mask(String strategy) {
        return AgentSession.CONTEXT_SUMMARY.equals(strategy) ? MaskPolicy.NONE : masking;
    }

    double compactionRatio(String strategy) {
        return AgentSession.CONTEXT_MASKING.equals(strategy) ? MASKING_SAFETY_RATIO : COMPACTION_RATIO;
    }
}
