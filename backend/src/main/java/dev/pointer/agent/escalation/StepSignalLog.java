package dev.pointer.agent.escalation;

import dev.pointer.common.Ids;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
class StepSignalLog {

    private static final TypeReference<Map<String, Integer>> SIGNALS_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    void record(
            UUID sessionId,
            UUID turnId,
            int step,
            String modelLabel,
            Map<String, Integer> signals,
            BigDecimal score,
            boolean escalated,
            boolean nudged) {
        jdbc.sql("""
                        insert into step_signals (id, session_id, turn_id, step, model_label, signals, score,
                                                  escalated, nudged, created_at)
                        values (:id, :sessionId, :turnId, :step, :modelLabel, cast(:signals as jsonb), :score,
                                :escalated, :nudged, :now)
                        """)
                .param("id", Ids.newId())
                .param("sessionId", sessionId)
                .param("turnId", turnId)
                .param("step", step)
                .param("modelLabel", modelLabel)
                .param("signals", json.writeValueAsString(signals))
                .param("score", score)
                .param("escalated", escalated)
                .param("nudged", nudged)
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    List<StepSignals> turn(UUID sessionId, UUID turnId) {
        return jdbc.sql("""
                        select step, model_label, signals::text as signals, score, escalated, nudged, created_at
                        from step_signals
                        where session_id = :sessionId and turn_id = :turnId
                        order by step, created_at
                        """)
                .param("sessionId", sessionId)
                .param("turnId", turnId)
                .query((rs, row) -> new StepSignals(
                        rs.getInt("step"),
                        rs.getString("model_label"),
                        json.readValue(rs.getString("signals"), SIGNALS_TYPE),
                        rs.getBigDecimal("score"),
                        rs.getBoolean("escalated"),
                        rs.getBoolean("nudged"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public record StepSignals(
            int step,
            String modelLabel,
            Map<String, Integer> signals,
            BigDecimal score,
            boolean escalated,
            boolean nudged,
            Instant createdAt) {}
}
