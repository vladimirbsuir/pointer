package dev.pointer.rag;

import dev.pointer.common.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class IndexStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    Optional<IndexState> find(UUID projectId) {
        return jdbc.sql("select * from index_states where project_id = :projectId")
                .param("projectId", projectId)
                .query(IndexStore::state)
                .optional();
    }

    boolean isSearchable(UUID projectId) {
        return jdbc.sql("""
                        select exists(
                            select 1 from index_states
                            where project_id = :projectId and chunk_count > 0 and embedding_model_id is not null)
                        """).param("projectId", projectId).query(Boolean.class).single();
    }

    void begin(UUID projectId, UUID userId, UUID modelId, String modelKey) {
        jdbc.sql("""
                        insert into index_states (project_id, user_id, embedding_model_id, embedding_model_key,
                                                  status, updated_at)
                        values (:projectId, :userId, :modelId, :modelKey, 'indexing', :now)
                        on conflict (project_id) do update
                        set user_id = excluded.user_id,
                            embedding_model_id = excluded.embedding_model_id,
                            embedding_model_key = excluded.embedding_model_key,
                            status = 'indexing', files_total = 0, files_done = 0, error = null,
                            updated_at = excluded.updated_at
                        """)
                .param("projectId", projectId)
                .param("userId", userId)
                .param("modelId", modelId)
                .param("modelKey", modelKey)
                .param("now", now())
                .update();
    }

    void progress(UUID projectId, int filesTotal, int filesDone) {
        jdbc.sql("""
                        update index_states set files_total = :total, files_done = :done, updated_at = :now
                        where project_id = :projectId
                        """)
                .param("projectId", projectId)
                .param("total", filesTotal)
                .param("done", filesDone)
                .param("now", now())
                .update();
    }

    void finish(UUID projectId, Integer dimensions) {
        jdbc.sql("""
                        update index_states
                        set status = 'ready', error = null, indexed_at = :now, updated_at = :now,
                            dimensions = coalesce(:dimensions, dimensions),
                            file_count = (select count(*) from indexed_files where project_id = :projectId),
                            chunk_count = (select count(*) from code_chunks where project_id = :projectId)
                        where project_id = :projectId
                        """)
                .param("projectId", projectId)
                .param("dimensions", dimensions)
                .param("now", now())
                .update();
    }

    void fail(UUID projectId, String error) {
        jdbc.sql("""
                        update index_states
                        set status = 'failed', error = :error, updated_at = :now,
                            file_count = (select count(*) from indexed_files where project_id = :projectId),
                            chunk_count = (select count(*) from code_chunks where project_id = :projectId)
                        where project_id = :projectId
                        """)
                .param("projectId", projectId)
                .param("error", error)
                .param("now", now())
                .update();
    }

    int failInterrupted() {
        return jdbc.sql("""
                        update index_states
                        set status = 'failed', error = 'Indexing was interrupted by a server restart',
                            updated_at = :now
                        where status = 'indexing'
                        """).param("now", now()).update();
    }

    @Transactional
    void clear(UUID projectId) {
        jdbc.sql("delete from code_chunks where project_id = :projectId")
                .param("projectId", projectId)
                .update();
        jdbc.sql("delete from indexed_files where project_id = :projectId")
                .param("projectId", projectId)
                .update();
    }

    @Transactional
    void delete(UUID projectId) {
        clear(projectId);
        jdbc.sql("delete from index_states where project_id = :projectId")
                .param("projectId", projectId)
                .update();
    }

    Map<String, String> hashes(UUID projectId) {
        return jdbc
                .sql("select path, content_hash from indexed_files where project_id = :projectId")
                .param("projectId", projectId)
                .query((rs, row) -> Map.entry(rs.getString("path"), rs.getString("content_hash")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Transactional
    void removeFiles(UUID projectId, Collection<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        jdbc.sql("delete from code_chunks where project_id = :projectId and path in (:paths)")
                .param("projectId", projectId)
                .param("paths", paths)
                .update();
        jdbc.sql("delete from indexed_files where project_id = :projectId and path in (:paths)")
                .param("projectId", projectId)
                .param("paths", paths)
                .update();
    }

    @Transactional
    void replaceFile(UUID projectId, String path, String hash, List<CodeChunk> chunks, List<float[]> embeddings) {
        jdbc.sql("delete from code_chunks where project_id = :projectId and path = :path")
                .param("projectId", projectId)
                .param("path", path)
                .update();
        for (var index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            jdbc.sql("""
                            insert into code_chunks (id, project_id, path, symbol, kind, start_line, end_line,
                                                     content, embedding)
                            values (:id, :projectId, :path, :symbol, :kind, :startLine, :endLine, :content,
                                    cast(:embedding as vector))
                            """)
                    .param("id", Ids.newId())
                    .param("projectId", projectId)
                    .param("path", path)
                    .param("symbol", chunk.symbol())
                    .param("kind", chunk.kind())
                    .param("startLine", chunk.startLine())
                    .param("endLine", chunk.endLine())
                    .param("content", chunk.content())
                    .param("embedding", literal(embeddings.get(index)))
                    .update();
        }
        jdbc.sql("""
                        insert into indexed_files (project_id, path, content_hash) values (:projectId, :path, :hash)
                        on conflict (project_id, path) do update set content_hash = excluded.content_hash
                        """)
                .param("projectId", projectId)
                .param("path", path)
                .param("hash", hash)
                .update();
    }

    List<SearchHit> search(UUID projectId, float[] query, String pathPrefix, int limit) {
        return jdbc.sql("""
                        select path, symbol, kind, start_line, end_line, content,
                               1 - (embedding <=> cast(:query as vector)) as score
                        from code_chunks
                        where project_id = :projectId and path like :prefix
                        order by embedding <=> cast(:query as vector)
                        limit :limit
                        """)
                .param("projectId", projectId)
                .param("query", literal(query))
                .param("prefix", escapeLike(pathPrefix) + "%")
                .param("limit", limit)
                .query((rs, row) -> new SearchHit(
                        new CodeChunk(
                                rs.getString("path"),
                                rs.getString("symbol"),
                                rs.getString("kind"),
                                rs.getInt("start_line"),
                                rs.getInt("end_line"),
                                rs.getString("content")),
                        rs.getDouble("score")))
                .list();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static String literal(float[] vector) {
        var builder = new StringBuilder(vector.length * 10).append('[');
        for (var index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static IndexState state(ResultSet rs, int row) throws SQLException {
        var indexedAt = rs.getTimestamp("indexed_at");
        return new IndexState(
                rs.getObject("project_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("embedding_model_id", UUID.class),
                rs.getString("embedding_model_key"),
                rs.getObject("dimensions", Integer.class),
                rs.getString("status"),
                rs.getInt("files_total"),
                rs.getInt("files_done"),
                rs.getInt("file_count"),
                rs.getInt("chunk_count"),
                rs.getString("error"),
                indexedAt == null ? null : indexedAt.toInstant());
    }

    record SearchHit(CodeChunk chunk, double score) {}
}
