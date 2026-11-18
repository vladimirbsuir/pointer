package dev.pointer.session;

import dev.pointer.common.Ids;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.provider.llm.ChatRequest;
import dev.pointer.provider.llm.StreamListener;
import dev.pointer.routing.PrefixFingerprint;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UsageLedger {

    public static final String MAIN = "main";
    public static final String STEP = "step";
    public static final String TITLE = "title";
    public static final String COMPACTION = "compaction";
    public static final String MEMORY = "memory";
    public static final String EXPLORE = "explore";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final PrefixFingerprint fingerprint;

    public void record(
            UUID sessionId,
            UUID turnId,
            ResolvedModel model,
            String role,
            String purpose,
            ChatRequest request,
            StreamListener.Usage usage) {
        record(sessionId, turnId, model, role, purpose, purpose, request, usage);
    }

    public void record(
            UUID sessionId,
            UUID turnId,
            ResolvedModel model,
            String role,
            String purpose,
            String prefixPurpose,
            ChatRequest request,
            StreamListener.Usage usage) {
        var reuse = fingerprint.measure(sessionId, prefixPurpose, model.modelId(), request);
        if (usage == null) {
            return;
        }
        jdbc.sql("""
                        insert into model_usage (id, session_id, turn_id, model_id, model_label, role, purpose,
                                                 prompt_tokens, completion_tokens, cached_prompt_tokens,
                                                 prefix_tokens, prefix_reuse_tokens, cost_usd, created_at)
                        values (:id, :sessionId, :turnId, :modelId, :label, :role, :purpose, :prompt, :completion,
                                :cached, :prefix, :reuse, :cost, :now)
                        """)
                .param("id", Ids.newId())
                .param("sessionId", sessionId)
                .param("turnId", turnId)
                .param("modelId", model.modelId())
                .param("label", model.displayName())
                .param("role", role)
                .param("purpose", purpose)
                .param("prompt", usage.promptTokens())
                .param("completion", usage.completionTokens())
                .param("cached", usage.cachedPromptTokens())
                .param("prefix", reuse.prefixTokens())
                .param("reuse", reuse.prefixReuseTokens())
                .param("cost", model.cost(usage))
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    public CompactionHistory compactionHistory(UUID sessionId) {
        return jdbc.sql("""
                        select avg(completion_tokens) filter (where purpose = 'compaction') as summary_tokens,
                               count(*) filter (where purpose = 'step') as step_calls,
                               count(distinct turn_id) filter (where purpose = 'step') as step_turns,
                               max(created_at) filter (where purpose in ('step', 'compaction')) as last_call_at
                        from model_usage
                        where session_id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .query((rs, row) -> {
                    var summaryTokens = rs.getObject("summary_tokens", BigDecimal.class);
                    var stepTurns = rs.getLong("step_turns");
                    var lastCallAt = rs.getTimestamp("last_call_at");
                    return new CompactionHistory(
                            summaryTokens == null ? null : summaryTokens.doubleValue(),
                            stepTurns == 0 ? null : (double) rs.getLong("step_calls") / stepTurns,
                            lastCallAt == null ? null : lastCallAt.toInstant());
                })
                .single();
    }

    List<SessionDtos.TurnSteps> turnSteps(UUID sessionId) {
        var turns = new LinkedHashMap<UUID, SessionDtos.TurnSteps>();
        jdbc.sql("""
                        select s.turn_id, t.number, s.model_label, count(*) as steps, bool_or(s.escalated) as escalated
                        from step_signals s
                        join turns t on t.id = s.turn_id
                        where s.session_id = :sessionId and t.rewound_at is null
                        group by s.turn_id, t.number, s.model_label
                        order by t.number, min(s.step)
                        """)
                .param("sessionId", sessionId)
                .query((rs, row) -> {
                    var turnId = rs.getObject("turn_id", UUID.class);
                    var earlier = turns.get(turnId);
                    var models = earlier == null ? new ArrayList<SessionDtos.ModelSteps>() : earlier.models();
                    models.add(new SessionDtos.ModelSteps(rs.getString("model_label"), rs.getLong("steps")));
                    var escalated = rs.getBoolean("escalated") || (earlier != null && earlier.escalated());
                    turns.put(turnId, new SessionDtos.TurnSteps(turnId, rs.getInt("number"), escalated, models));
                    return turnId;
                })
                .list();
        return List.copyOf(turns.values());
    }

    List<UsageRow> summary(UUID sessionId) {
        return jdbc.sql("""
                        select model_id, model_label, role, purpose, count(*) as calls,
                               sum(prompt_tokens) as prompt_tokens, sum(completion_tokens) as completion_tokens,
                               sum(cached_prompt_tokens)::bigint as cached_prompt_tokens,
                               (sum(prompt_tokens) filter (where cached_prompt_tokens is not null))::bigint
                                   as cache_reported_prompt_tokens,
                               (sum(prefix_tokens) filter (where prefix_reuse_tokens is not null))::bigint
                                   as prefix_tokens,
                               sum(prefix_reuse_tokens)::bigint as prefix_reuse_tokens,
                               sum(cost_usd) as cost_usd
                        from model_usage
                        where session_id = :sessionId
                        group by model_id, model_label, role, purpose
                        order by sum(prompt_tokens + completion_tokens) desc
                        """)
                .param("sessionId", sessionId)
                .query((rs, row) -> new UsageRow(
                        rs.getObject("model_id", UUID.class),
                        rs.getString("model_label"),
                        rs.getString("role"),
                        rs.getString("purpose"),
                        rs.getLong("calls"),
                        rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"),
                        rs.getObject("cached_prompt_tokens", Long.class),
                        rs.getObject("cache_reported_prompt_tokens", Long.class),
                        rs.getObject("prefix_tokens", Long.class),
                        rs.getObject("prefix_reuse_tokens", Long.class),
                        rs.getBigDecimal("cost_usd")))
                .list();
    }

    public record CompactionHistory(Double averageSummaryTokens, Double averageCallsPerTurn, Instant lastCallAt) {}

    record UsageRow(
            UUID modelId,
            String modelLabel,
            String role,
            String purpose,
            long calls,
            long promptTokens,
            long completionTokens,
            Long cachedPromptTokens,
            Long cacheReportedPromptTokens,
            Long prefixTokens,
            Long prefixReuseTokens,
            BigDecimal costUsd) {}
}
