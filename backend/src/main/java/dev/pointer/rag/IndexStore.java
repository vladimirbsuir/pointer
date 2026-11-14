package dev.pointer.rag;

import dev.pointer.common.Ids;
import dev.pointer.provider.EmbeddingPrefixes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
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

    private static final String LEXICAL_VECTOR =
            "setweight(to_tsvector('english', :head), 'A') || setweight(to_tsvector('english', :body), 'B')";

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

    void begin(UUID projectId, UUID userId, UUID modelId, String modelKey, EmbeddingPrefixes prefixes) {
        jdbc.sql("""
                        insert into index_states (project_id, user_id, embedding_model_id, embedding_model_key,
                                                  embedding_query_prefix, embedding_document_prefix,
                                                  status, updated_at)
                        values (:projectId, :userId, :modelId, :modelKey, :queryPrefix, :documentPrefix,
                                'indexing', :now)
                        on conflict (project_id) do update
                        set user_id = excluded.user_id,
                            embedding_model_id = excluded.embedding_model_id,
                            embedding_model_key = excluded.embedding_model_key,
                            embedding_query_prefix = excluded.embedding_query_prefix,
                            embedding_document_prefix = excluded.embedding_document_prefix,
                            status = 'indexing', files_total = 0, files_done = 0, error = null,
                            updated_at = excluded.updated_at
                        """)
                .param("projectId", projectId)
                .param("userId", userId)
                .param("modelId", modelId)
                .param("modelKey", modelKey)
                .param("queryPrefix", prefixes.query())
                .param("documentPrefix", prefixes.document())
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
                                                     content, embedding, lexical)
                            values (:id, :projectId, :path, :symbol, :kind, :startLine, :endLine, :content,
                                    cast(:embedding as vector),
                            """ + LEXICAL_VECTOR + ")")
                    .param("id", Ids.newId())
                    .param("projectId", projectId)
                    .param("path", path)
                    .param("symbol", chunk.symbol())
                    .param("kind", chunk.kind())
                    .param("startLine", chunk.startLine())
                    .param("endLine", chunk.endLine())
                    .param("content", chunk.content())
                    .param("embedding", literal(embeddings.get(index)))
                    .param("head", lexicalHead(path, chunk.symbol()))
                    .param("body", CodeTokens.expand(chunk.content()))
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

    List<Candidate> vectorCandidates(UUID projectId, float[] query, String pathPrefix, int limit) {
        return jdbc.sql("""
                        select id, path, symbol, kind, start_line, end_line, content,
                               1 - (embedding <=> cast(:query as vector)) as similarity
                        from code_chunks
                        where project_id = :projectId and path like :prefix
                        order by embedding <=> cast(:query as vector)
                        limit :limit
                        """)
                .param("projectId", projectId)
                .param("query", literal(query))
                .param("prefix", escapeLike(pathPrefix) + "%")
                .param("limit", limit)
                .query(IndexStore::candidate)
                .list();
    }

    List<Candidate> lexicalCandidates(UUID projectId, float[] query, List<String> terms, String pathPrefix, int limit) {
        if (terms.isEmpty()) {
            return List.of();
        }
        var weights = termWeights(projectId, terms);
        var score = new StringBuilder();
        for (var index = 0; index < terms.size(); index++) {
            if (index > 0) {
                score.append(" + ");
            }
            score.append(":w")
                    .append(index)
                    .append(" * ts_rank(lexical, to_tsquery('english', :t")
                    .append(index)
                    .append("), 1)");
        }
        var statement = jdbc.sql("""
                        select id, path, symbol, kind, start_line, end_line, content,
                               case when cast(:query as vector) is null then 0
                                    else 1 - (embedding <=> cast(:query as vector)) end as similarity
                        from code_chunks
                        where project_id = :projectId and path like :prefix
                          and lexical @@ to_tsquery('english', :terms)
                        order by %s desc, id
                        limit :limit
                        """.formatted(score))
                .param("projectId", projectId)
                .param("query", query == null ? null : literal(query))
                .param("terms", CodeTokens.tsQuery(terms))
                .param("prefix", escapeLike(pathPrefix) + "%")
                .param("limit", limit);
        for (var index = 0; index < terms.size(); index++) {
            statement = statement.param("t" + index, terms.get(index)).param("w" + index, weights.get(index));
        }
        return statement.query(IndexStore::candidate).list();
    }

    private List<Double> termWeights(UUID projectId, List<String> terms) {
        var counts = new StringBuilder("select count(*) as total");
        for (var index = 0; index < terms.size(); index++) {
            counts.append(", count(*) filter (where lexical @@ to_tsquery('english', :t")
                    .append(index)
                    .append(")) as d")
                    .append(index);
        }
        var statement = jdbc.sql(counts.append(" from code_chunks where project_id = :projectId")
                        .toString())
                .param("projectId", projectId);
        for (var index = 0; index < terms.size(); index++) {
            statement = statement.param("t" + index, terms.get(index));
        }
        return statement
                .query((rs, row) -> {
                    var total = rs.getLong("total");
                    var weights = new ArrayList<Double>(terms.size());
                    for (var index = 0; index < terms.size(); index++) {
                        weights.add(inverseDocumentFrequency(total, rs.getLong("d" + index)));
                    }
                    return weights;
                })
                .single();
    }

    static double inverseDocumentFrequency(long total, long containing) {
        return Math.log(1 + (total - containing + 0.5) / (containing + 0.5));
    }

    @Transactional
    int rebuildLexical(UUID projectId, int version) {
        var rows = jdbc.sql("select id, path, symbol, content from code_chunks where project_id = :projectId")
                .param("projectId", projectId)
                .query((rs, row) -> new LexicalSource(
                        rs.getObject("id", UUID.class),
                        rs.getString("path"),
                        rs.getString("symbol"),
                        rs.getString("content")))
                .list();
        for (var row : rows) {
            jdbc.sql("update code_chunks set lexical = " + LEXICAL_VECTOR + " where id = :id")
                    .param("id", row.id())
                    .param("head", lexicalHead(row.path(), row.symbol()))
                    .param("body", CodeTokens.expand(row.content()))
                    .update();
        }
        jdbc.sql("update index_states set lexical_version = :version where project_id = :projectId")
                .param("projectId", projectId)
                .param("version", version)
                .update();
        return rows.size();
    }

    void changeStrategy(UUID projectId, SearchStrategy strategy) {
        jdbc.sql("update index_states set search_strategy = :strategy, updated_at = :now where project_id = :projectId")
                .param("projectId", projectId)
                .param("strategy", strategy.wireName())
                .param("now", now())
                .update();
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

    private static String lexicalHead(String path, String symbol) {
        return CodeTokens.expand(symbol == null ? path : path + " " + symbol);
    }

    private static Candidate candidate(ResultSet rs, int row) throws SQLException {
        return new Candidate(
                rs.getObject("id", UUID.class),
                new CodeChunk(
                        rs.getString("path"),
                        rs.getString("symbol"),
                        rs.getString("kind"),
                        rs.getInt("start_line"),
                        rs.getInt("end_line"),
                        rs.getString("content")),
                rs.getDouble("similarity"));
    }

    private static IndexState state(ResultSet rs, int row) throws SQLException {
        var indexedAt = rs.getTimestamp("indexed_at");
        return new IndexState(
                rs.getObject("project_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("embedding_model_id", UUID.class),
                rs.getString("embedding_model_key"),
                new EmbeddingPrefixes(
                        rs.getString("embedding_query_prefix"), rs.getString("embedding_document_prefix")),
                rs.getObject("dimensions", Integer.class),
                rs.getString("status"),
                rs.getInt("files_total"),
                rs.getInt("files_done"),
                rs.getInt("file_count"),
                rs.getInt("chunk_count"),
                rs.getString("error"),
                indexedAt == null ? null : indexedAt.toInstant(),
                rs.getInt("lexical_version"),
                SearchStrategy.fromWireName(rs.getString("search_strategy")));
    }

    record Candidate(UUID id, CodeChunk chunk, double similarity) {}

    record SearchHit(CodeChunk chunk, double score, Integer vectorRank, Integer lexicalRank) {}

    private record LexicalSource(UUID id, String path, String symbol, String content) {}
}
