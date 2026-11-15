package dev.pointer.session;

import dev.pointer.common.Ids;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.provider.llm.StreamListener;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
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

    public void record(
            UUID sessionId, UUID turnId, ResolvedModel model, String role, String purpose, StreamListener.Usage usage) {
        if (usage == null) {
            return;
        }
        jdbc.sql("""
                        insert into model_usage (id, session_id, turn_id, model_id, model_label, role, purpose,
                                                 prompt_tokens, completion_tokens, cost_usd, created_at)
                        values (:id, :sessionId, :turnId, :modelId, :label, :role, :purpose, :prompt, :completion,
                                :cost, :now)
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
                .param("cost", model.cost(usage.promptTokens(), usage.completionTokens()))
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    List<UsageRow> summary(UUID sessionId) {
        return jdbc.sql("""
                        select model_id, model_label, role, purpose, count(*) as calls,
                               sum(prompt_tokens) as prompt_tokens, sum(completion_tokens) as completion_tokens,
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
                        rs.getBigDecimal("cost_usd")))
                .list();
    }

    record UsageRow(
            UUID modelId,
            String modelLabel,
            String role,
            String purpose,
            long calls,
            long promptTokens,
            long completionTokens,
            BigDecimal costUsd) {}
}
