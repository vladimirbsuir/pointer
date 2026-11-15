package dev.pointer.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class DigestStore {

    static final int MAX_ATTEMPTS = 3;
    private static final String DOCUMENT = "to_tsvector('english', s.title || ' ' || coalesce(d.summary, ''))";

    private final JdbcClient jdbc;
    private final Clock clock;

    List<Candidate> candidates(Instant idleBefore, Instant activeAfter, int minUserMessages, int limit) {
        return jdbc.sql("""
                        select s.id, s.user_id, s.project_id, s.model_id, s.executor_device_id, s.last_seq
                        from sessions s
                        left join session_digests d on d.session_id = s.id
                        where s.status = 'idle'
                          and s.model_id is not null
                          and (s.updated_at < :idleBefore or s.archived_at is not null)
                          and s.updated_at > :activeAfter
                          and (d.session_id is null or (d.processed_seq < s.last_seq and d.attempts < :maxAttempts))
                          and (select count(*) from items i where i.session_id = s.id and i.type = 'user_message')
                              >= :minUserMessages
                        order by s.updated_at
                        limit :limit
                        """)
                .param("idleBefore", Timestamp.from(idleBefore))
                .param("activeAfter", Timestamp.from(activeAfter))
                .param("maxAttempts", MAX_ATTEMPTS)
                .param("minUserMessages", minUserMessages)
                .param("limit", limit)
                .query((rs, row) -> new Candidate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("model_id", UUID.class),
                        rs.getObject("executor_device_id", UUID.class),
                        rs.getLong("last_seq")))
                .list();
    }

    void saveDigest(Candidate session, String summary, int suggested) {
        jdbc.sql("""
                        insert into session_digests (session_id, user_id, project_id, summary, processed_seq, attempts,
                                                     suggested_count, updated_at)
                        values (:sessionId, :userId, :projectId, :summary, :seq, 0, :suggested, :now)
                        on conflict (session_id) do update
                        set summary = excluded.summary, processed_seq = excluded.processed_seq, attempts = 0,
                            suggested_count = session_digests.suggested_count + excluded.suggested_count,
                            embedding = null, embedding_model_id = null, updated_at = excluded.updated_at
                        """)
                .param("sessionId", session.sessionId())
                .param("userId", session.userId())
                .param("projectId", session.projectId())
                .param("summary", summary)
                .param("seq", session.lastSeq())
                .param("suggested", suggested)
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    void recordFailure(Candidate session) {
        jdbc.sql("""
                        insert into session_digests (session_id, user_id, project_id, attempts, updated_at)
                        values (:sessionId, :userId, :projectId, 1, :now)
                        on conflict (session_id) do update
                        set attempts = session_digests.attempts + 1, updated_at = excluded.updated_at
                        """)
                .param("sessionId", session.sessionId())
                .param("userId", session.userId())
                .param("projectId", session.projectId())
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    void saveEmbedding(UUID sessionId, UUID modelId, String vector) {
        jdbc.sql("update session_digests set embedding = cast(:vector as vector), embedding_model_id = :modelId"
                        + " where session_id = :sessionId")
                .param("sessionId", sessionId)
                .param("modelId", modelId)
                .param("vector", vector)
                .update();
    }

    List<Hit> vectorSearch(UUID userId, UUID projectId, UUID excludeSessionId, UUID modelId, String vector, int limit) {
        return jdbc.sql("""
                        select s.id, s.title, s.updated_at, d.summary,
                               1 - (d.embedding <=> cast(:vector as vector)) as score
                        from session_digests d join sessions s on s.id = d.session_id
                        where d.user_id = :userId and d.project_id = :projectId and d.session_id <> :exclude
                          and d.summary is not null and d.embedding_model_id = :modelId
                        order by d.embedding <=> cast(:vector as vector)
                        limit :limit
                        """)
                .param("userId", userId)
                .param("projectId", projectId)
                .param("exclude", excludeSessionId)
                .param("modelId", modelId)
                .param("vector", vector)
                .param("limit", limit)
                .query((rs, row) -> hit(rs))
                .list();
    }

    List<Hit> lexicalSearch(UUID userId, UUID projectId, UUID excludeSessionId, String tsQuery, int limit) {
        if (tsQuery.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select s.id, s.title, s.updated_at, d.summary, ts_rank(" + DOCUMENT
                        + ", to_tsquery('english', :terms)) as score"
                        + " from session_digests d join sessions s on s.id = d.session_id"
                        + " where d.user_id = :userId and d.project_id = :projectId and d.session_id <> :exclude"
                        + " and d.summary is not null and " + DOCUMENT + " @@ to_tsquery('english', :terms)"
                        + " order by score desc, s.updated_at desc limit :limit")
                .param("userId", userId)
                .param("projectId", projectId)
                .param("exclude", excludeSessionId)
                .param("terms", tsQuery)
                .param("limit", limit)
                .query((rs, row) -> hit(rs))
                .list();
    }

    private static Hit hit(ResultSet rs) throws SQLException {
        return new Hit(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("summary"),
                rs.getDouble("score"));
    }

    record Candidate(UUID sessionId, UUID userId, UUID projectId, UUID modelId, UUID deviceId, long lastSeq) {}

    record Hit(UUID sessionId, String title, Instant updatedAt, String summary, double score) {}
}
