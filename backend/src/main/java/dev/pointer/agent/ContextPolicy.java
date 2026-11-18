package dev.pointer.agent;

import dev.pointer.session.AgentSession;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ContextPolicy {

    static final double COMPACTION_RATIO = 0.8;
    static final double MASKING_SAFETY_RATIO = 0.95;

    private final MaskPolicy masking;
    private final boolean softCompaction;
    private final Duration cacheLifetime;

    ContextPolicy(
            @Value("${pointer.context.keep-results:10}") int keepResults,
            @Value("${pointer.context.mask-block:10}") int maskBlock,
            @Value("${pointer.context.soft-compaction:true}") boolean softCompaction,
            @Value("${pointer.context.cache-lifetime:5m}") Duration cacheLifetime) {
        this.masking = new MaskPolicy(keepResults, maskBlock);
        this.softCompaction = softCompaction;
        this.cacheLifetime = cacheLifetime;
    }

    MaskPolicy mask(String strategy) {
        return AgentSession.CONTEXT_SUMMARY.equals(strategy) ? MaskPolicy.NONE : masking;
    }

    double compactionRatio(String strategy) {
        return AgentSession.CONTEXT_MASKING.equals(strategy) ? MASKING_SAFETY_RATIO : COMPACTION_RATIO;
    }

    boolean softCompaction(String strategy) {
        return softCompaction && !AgentSession.CONTEXT_MASKING.equals(strategy);
    }

    Duration cacheLifetime() {
        return cacheLifetime;
    }
}
