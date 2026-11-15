package dev.pointer.memory;

import dev.pointer.common.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MemoryStore {

    private static final String COLUMNS = """
            id, user_id, project_id, kind, name, description, content, status, pinned, source, source_session_id,
            read_count, last_read_at, created_at, updated_at""";
    private static final String VISIBLE =
            "user_id = :userId and (project_id is null or project_id = cast(:projectId as uuid))";
    private static final String DOCUMENT =
            "to_tsvector('english', translate(name, '-', ' ') || ' ' || description || ' ' || content)";

    private final JdbcClient jdbc;
    private final Clock clock;

    Optional<Memory> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from memories where id = :id")
                .param("id", id)
                .query(MemoryStore::memory)
                .optional();
    }

    Optional<Memory> findByName(UUID userId, UUID projectId, String name) {
        return jdbc.sql("select " + COLUMNS + """
                         from memories
                        where user_id = :userId and project_id is not distinct from cast(:projectId as uuid) and name = :name
                          and status in ('active', 'suggested')
                        """)
                .param("userId", userId)
                .param("projectId", projectId)
                .param("name", name)
                .query(MemoryStore::memory)
                .optional();
    }

    List<Memory> listScope(UUID userId, UUID projectId, String status) {
        return jdbc.sql("select " + COLUMNS + """
                         from memories
                        where user_id = :userId and project_id is not distinct from cast(:projectId as uuid) and status = :status
                        order by pinned desc, updated_at desc
                        """)
                .param("userId", userId)
                .param("projectId", projectId)
                .param("status", status)
                .query(MemoryStore::memory)
                .list();
    }

    List<Memory> visible(UUID userId, UUID projectId) {
        return jdbc.sql("select " + COLUMNS + " from memories where " + VISIBLE + """
                          and status = 'active'
                        order by pinned desc, (project_id is null), updated_at desc
                        """)
                .param("userId", userId)
                .param("projectId", projectId)
                .query(MemoryStore::memory)
                .list();
    }

    List<Memory> recent(UUID userId, UUID projectId, int limit) {
        return jdbc.sql("select " + COLUMNS + " from memories where " + VISIBLE + """
                          and status = 'active'
                        order by updated_at desc
                        limit :limit
                        """)
                .param("userId", userId)
                .param("projectId", projectId)
                .param("limit", limit)
                .query(MemoryStore::memory)
                .list();
    }

    List<Memory> byNames(UUID userId, UUID projectId, Collection<String> names) {
        return jdbc.sql("select " + COLUMNS + " from memories where " + VISIBLE + """
                          and status = 'active' and name in (:names)
                        order by (project_id is null), name
                        """)
                .param("userId", userId)
                .param("projectId", projectId)
                .param("names", names)
                .query(MemoryStore::memory)
                .list();
    }

    List<ScoredMemory> vectorSearch(UUID userId, UUID projectId, UUID modelId, float[] query, int limit) {
        return jdbc.sql("select " + COLUMNS + ", 1 - (embedding <=> cast(:query as vector)) as score"
                        + " from memories where " + VISIBLE + """
                          and status = 'active' and embedding_model_id = :modelId and embedding is not null
                        order by embedding <=> cast(:query as vector)
                        limit :limit
                        """)
                .param("userId", userId)
                .param("projectId", projectId)
                .param("modelId", modelId)
                .param("query", literal(query))
                .param("limit", limit)
                .query((rs, row) -> new ScoredMemory(memory(rs, row), rs.getDouble("score")))
                .list();
    }

    List<ScoredMemory> lexicalSearch(UUID userId, UUID projectId, String tsQuery, int limit) {
        if (tsQuery.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COLUMNS + ", ts_rank(" + DOCUMENT + ", to_tsquery('english', :terms)) as score"
                        + " from memories where " + VISIBLE + " and status = 'active' and " + DOCUMENT
                        + " @@ to_tsquery('english', :terms) order by score desc, updated_at desc limit :limit")
                .param("userId", userId)
                .param("projectId", projectId)
                .param("terms", tsQuery)
                .param("limit", limit)
                .query((rs, row) -> new ScoredMemory(memory(rs, row), rs.getDouble("score")))
                .list();
    }

    Memory insert(Memory memory) {
        jdbc.sql("""
                        insert into memories (id, user_id, project_id, kind, name, description, content, status, pinned,
                                              source, source_session_id, created_at, updated_at)
                        values (:id, :userId, :projectId, :kind, :name, :description, :content, :status, :pinned,
                                :source, :sourceSessionId, :now, :now)
                        """)
                .param("id", memory.id())
                .param("userId", memory.userId())
                .param("projectId", memory.projectId())
                .param("kind", memory.kind())
                .param("name", memory.name())
                .param("description", memory.description())
                .param("content", memory.content())
                .param("status", memory.status())
                .param("pinned", memory.pinned())
                .param("source", memory.source())
                .param("sourceSessionId", memory.sourceSessionId())
                .param("now", Timestamp.from(memory.createdAt()))
                .update();
        return memory;
    }

    void update(Memory memory) {
        jdbc.sql("""
                        update memories
                        set kind = :kind, description = :description, content = :content, status = :status,
                            pinned = :pinned, updated_at = :now
                        where id = :id
                        """)
                .param("id", memory.id())
                .param("kind", memory.kind())
                .param("description", memory.description())
                .param("content", memory.content())
                .param("status", memory.status())
                .param("pinned", memory.pinned())
                .param("now", Timestamp.from(now()))
                .update();
    }

    void markRead(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("update memories set read_count = read_count + 1, last_read_at = :now where id in (:ids)")
                .param("ids", ids)
                .param("now", Timestamp.from(now()))
                .update();
    }

    void saveEmbedding(UUID id, UUID modelId, float[] embedding) {
        jdbc.sql("update memories set embedding = cast(:embedding as vector), embedding_model_id = :modelId"
                        + " where id = :id")
                .param("id", id)
                .param("modelId", modelId)
                .param("embedding", literal(embedding))
                .update();
    }

    void saveRevision(Memory previous, UUID memoryId) {
        jdbc.sql("""
                        insert into memory_revisions (id, memory_id, kind, description, content, status, pinned,
                                                      created_at)
                        values (:id, :memoryId, :kind, :description, :content, :status, :pinned, :now)
                        """)
                .param("id", Ids.newId())
                .param("memoryId", memoryId)
                .param("kind", previous == null ? null : previous.kind())
                .param("description", previous == null ? null : previous.description())
                .param("content", previous == null ? null : previous.content())
                .param("status", previous == null ? null : previous.status())
                .param("pinned", previous == null ? null : previous.pinned())
                .param("now", Timestamp.from(now()))
                .update();
    }

    Optional<Revision> latestRevision(UUID memoryId) {
        return jdbc.sql("""
                        select id, kind, description, content, status, pinned from memory_revisions
                        where memory_id = :memoryId
                        order by created_at desc, id desc
                        limit 1
                        """)
                .param("memoryId", memoryId)
                .query((rs, row) -> new Revision(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("description"),
                        rs.getString("content"),
                        rs.getString("status"),
                        rs.getObject("pinned", Boolean.class)))
                .optional();
    }

    void deleteRevision(UUID revisionId) {
        jdbc.sql("delete from memory_revisions where id = :id")
                .param("id", revisionId)
                .update();
    }

    Instant now() {
        return clock.instant();
    }

    static String literal(float[] vector) {
        var builder = new StringBuilder(vector.length * 10).append('[');
        for (var index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    private static Memory memory(ResultSet rs, int row) throws SQLException {
        var lastReadAt = rs.getTimestamp("last_read_at");
        return new Memory(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getBoolean("pinned"),
                rs.getString("source"),
                rs.getObject("source_session_id", UUID.class),
                rs.getInt("read_count"),
                lastReadAt == null ? null : lastReadAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    record ScoredMemory(Memory memory, double score) {}

    record Revision(UUID id, String kind, String description, String content, String status, Boolean pinned) {}
}
