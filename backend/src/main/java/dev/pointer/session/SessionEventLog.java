package dev.pointer.session;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class SessionEventLog {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SessionEventBus bus;
    private final Clock clock;

    SessionEventLog(JdbcClient jdbc, JsonMapper json, SessionEventBus bus, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.bus = bus;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    SessionEventRecord append(UUID userId, UUID sessionId, String kind, Map<String, Object> payload) {
        var seq = jdbc.sql("update sessions set last_seq = last_seq + 1 where id = :id returning last_seq")
                .param("id", sessionId)
                .query(Long.class)
                .single();
        var occurredAt = clock.instant();
        var event = new LinkedHashMap<String, Object>();
        event.put("kind", kind);
        event.putAll(payload);
        jdbc.sql("""
                        insert into session_events (session_id, seq, kind, payload, occurred_at)
                        values (:sessionId, :seq, :kind, cast(:payload as jsonb), :occurredAt)
                        """)
                .param("sessionId", sessionId)
                .param("seq", seq)
                .param("kind", kind)
                .param("payload", json.writeValueAsString(event))
                .param("occurredAt", Timestamp.from(occurredAt))
                .update();
        var record = new SessionEventRecord(sessionId, seq, occurredAt, event);
        afterCommit(() -> bus.publishEvent(userId, record));
        return record;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    long nextPosition(UUID sessionId) {
        return jdbc.sql("update sessions set last_position = last_position + 1 where id = :id returning last_position")
                .param("id", sessionId)
                .query(Long.class)
                .single();
    }

    long lastSeq(UUID sessionId) {
        return jdbc.sql("select last_seq from sessions where id = :id")
                .param("id", sessionId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    @Transactional(readOnly = true)
    List<SessionEventRecord> after(UUID sessionId, long afterSeq, int limit) {
        return jdbc.sql("""
                        select seq, payload::text as payload, occurred_at from session_events
                        where session_id = :sessionId and seq > :afterSeq order by seq limit :limit
                        """)
                .param("sessionId", sessionId)
                .param("afterSeq", afterSeq)
                .param("limit", limit)
                .query((rs, row) -> new SessionEventRecord(
                        sessionId,
                        rs.getLong("seq"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        json.readValue(rs.getString("payload"), MAP_TYPE)))
                .list();
    }

    void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
