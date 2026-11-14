package dev.pointer.rag;

import dev.pointer.common.error.ApiException;
import dev.pointer.project.ProjectService;
import dev.pointer.provider.EmbeddingPrefixes;
import dev.pointer.provider.ProviderService;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.LlmException;
import dev.pointer.user.SettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IndexService {

    static final String FRESH = "fresh";
    static final String UPDATING = "updating";
    static final String OFFLINE = "offline";
    private static final Logger log = LoggerFactory.getLogger(IndexService.class);
    private static final int FILES_PER_REQUEST = 20;
    private static final int MAX_CHANGES_BEFORE_SEARCH = 50;
    static final int LEXICAL_VERSION = 1;
    static final int FUSION_CANDIDATES = 50;
    private static final int QUERY_DEFAULT_LIMIT = 10;

    private final IndexStore store;
    private final DeviceIndexClient device;
    private final Embedder embedder;
    private final SettingsService settings;
    private final ProjectService projects;
    private final ProviderService providers;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    @EventListener(ApplicationReadyEvent.class)
    void failInterrupted() {
        var count = store.failInterrupted();
        if (count > 0) {
            log.info("Marked {} project indexes as interrupted after restart", count);
        }
    }

    public IndexDtos.IndexStatusResponse status(UUID userId, UUID projectId) {
        projects.owned(userId, projectId);
        return response(store.find(projectId).orElse(null));
    }

    public IndexDtos.IndexStatusResponse start(UUID userId, UUID deviceId, UUID projectId, boolean rebuild) {
        projects.owned(userId, projectId);
        var modelId = settings.embeddingModel(userId);
        if (modelId == null) {
            throw ApiException.badRequest(
                    "embedding_model_not_configured", "Choose an embedding model in the settings first");
        }
        if (!device.isOnline(projectId, deviceId)) {
            throw ApiException.conflict("index_device_offline", "Open the project on this computer to index it");
        }
        var model = embedder.resolve(userId, modelId, deviceId);
        var prefixes = model.embeddingPrefixes();
        if (!running.add(projectId)) {
            throw ApiException.conflict("index_running", "The project is already being indexed");
        }
        try {
            var previous = store.find(projectId).orElse(null);
            if (rebuild
                    || previous == null
                    || !modelId.equals(previous.embeddingModelId())
                    || !prefixes.equals(previous.prefixes())) {
                store.clear(projectId);
            }
            store.begin(projectId, userId, modelId, model.modelKey(), prefixes);
        } catch (RuntimeException exception) {
            running.remove(projectId);
            throw exception;
        }
        Thread.ofVirtual().name("index-" + projectId).start(() -> {
            try {
                var cancellation = new Cancellation();
                rebuildLexicalIfOutdated(projectId);
                var changes = changes(projectId, deviceId, cancellation);
                var dimensions = apply(projectId, deviceId, model, prefixes, changes, cancellation);
                store.finish(projectId, dimensions);
            } catch (IndexException | LlmException | ApiException exception) {
                store.fail(projectId, exception.getMessage());
            } catch (RuntimeException exception) {
                log.warn("Indexing of project {} failed", projectId, exception);
                store.fail(projectId, "Indexing failed unexpectedly");
            } finally {
                running.remove(projectId);
            }
        });
        return response(store.find(projectId).orElse(null));
    }

    public void delete(UUID userId, UUID projectId) {
        projects.owned(userId, projectId);
        if (running.contains(projectId)) {
            throw ApiException.conflict("index_running", "Wait until indexing finishes");
        }
        store.delete(projectId);
    }

    boolean isSearchable(UUID projectId) {
        return store.isSearchable(projectId);
    }

    SearchOutcome search(
            UUID projectId, UUID deviceId, String query, String pathPrefix, int limit, Cancellation cancellation) {
        var state = store.find(projectId)
                .filter(found -> found.embeddingModelId() != null)
                .orElseThrow(() -> new IndexException("index_missing", "The project has no semantic index"));
        var model = embedder.resolve(state.userId(), state.embeddingModelId(), deviceId);
        upgradeLexical(state);
        var freshness = refresh(projectId, deviceId, model, state.prefixes(), cancellation);
        var hits = rank(
                projectId, model, state.prefixes(), state.searchStrategy(), query, pathPrefix, limit, cancellation);
        return new SearchOutcome(hits, freshness);
    }

    public IndexDtos.IndexQueryResponse query(
            UUID userId, UUID deviceId, UUID projectId, IndexDtos.IndexQueryRequest request) {
        projects.owned(userId, projectId);
        var state = store.find(projectId)
                .filter(found -> found.embeddingModelId() != null)
                .orElseThrow(() -> ApiException.notFound("index_missing", "The project has no semantic index"));
        var strategy =
                request.strategy() == null ? state.searchStrategy() : SearchStrategy.fromWireName(request.strategy());
        var limit = request.limit() == null ? QUERY_DEFAULT_LIMIT : request.limit();
        var cancellation = new Cancellation();
        var model = embedder.resolve(state.userId(), state.embeddingModelId(), deviceId);
        upgradeLexical(state);
        refresh(projectId, deviceId, model, state.prefixes(), cancellation);
        var started = System.nanoTime();
        var hits = rank(
                projectId,
                model,
                state.prefixes(),
                strategy,
                request.query().strip(),
                SemanticSearchOperation.pathPrefix(request.path()),
                limit,
                cancellation);
        var tookMs = (System.nanoTime() - started) / 1_000_000;
        return new IndexDtos.IndexQueryResponse(
                strategy.wireName(),
                tookMs,
                hits.stream()
                        .map(hit -> new IndexDtos.IndexQueryHit(
                                hit.chunk().path(),
                                hit.chunk().symbol(),
                                hit.chunk().kind(),
                                hit.chunk().startLine(),
                                hit.chunk().endLine(),
                                hit.score(),
                                hit.vectorRank(),
                                hit.lexicalRank()))
                        .toList());
    }

    public IndexDtos.IndexStatusResponse changeStrategy(UUID userId, UUID projectId, String strategyName) {
        projects.owned(userId, projectId);
        var strategy = SearchStrategy.fromWireName(strategyName);
        if (strategy == SearchStrategy.LEXICAL) {
            throw ApiException.badRequest("invalid_strategy", "Semantic search needs vectors: use vector or hybrid");
        }
        if (store.find(projectId).isEmpty()) {
            throw ApiException.notFound("index_missing", "The project has no semantic index");
        }
        store.changeStrategy(projectId, strategy);
        return response(store.find(projectId).orElse(null));
    }

    private List<IndexStore.SearchHit> rank(
            UUID projectId,
            ResolvedModel model,
            EmbeddingPrefixes prefixes,
            SearchStrategy strategy,
            String query,
            String pathPrefix,
            int limit,
            Cancellation cancellation) {
        var queryVector = strategy.usesVectors() ? embedder.embedQuery(model, prefixes, query, cancellation) : null;
        var candidates = strategy == SearchStrategy.VECTOR ? limit : FUSION_CANDIDATES;
        var vector = strategy.usesVectors()
                ? store.vectorCandidates(projectId, queryVector, pathPrefix, candidates)
                : List.<IndexStore.Candidate>of();
        var lexical = strategy.usesLexical()
                ? store.lexicalCandidates(projectId, queryVector, CodeTokens.queryTerms(query), pathPrefix, candidates)
                : List.<IndexStore.Candidate>of();
        return RankFusion.fuse(vector, lexical, limit);
    }

    private void upgradeLexical(IndexState state) {
        if (state.lexicalVersion() >= LEXICAL_VERSION || !running.add(state.projectId())) {
            return;
        }
        try {
            rebuildLexicalIfOutdated(state.projectId());
        } finally {
            running.remove(state.projectId());
        }
    }

    private void rebuildLexicalIfOutdated(UUID projectId) {
        var outdated = store.find(projectId)
                .filter(state -> state.lexicalVersion() < LEXICAL_VERSION)
                .isPresent();
        if (outdated) {
            var count = store.rebuildLexical(projectId, LEXICAL_VERSION);
            log.info("Rebuilt full-text tokens of {} chunks in project {}", count, projectId);
        }
    }

    private IndexDtos.IndexStatusResponse response(IndexState state) {
        return IndexDtos.IndexStatusResponse.of(state, state != null && prefixesChanged(state));
    }

    private boolean prefixesChanged(IndexState state) {
        return state.embeddingModelId() != null
                && providers
                        .embeddingPrefixes(state.embeddingModelId())
                        .map(current -> !current.equals(state.prefixes()))
                        .orElse(false);
    }

    private String refresh(
            UUID projectId, UUID deviceId, ResolvedModel model, EmbeddingPrefixes prefixes, Cancellation cancellation) {
        if (!device.isOnline(projectId, deviceId)) {
            return OFFLINE;
        }
        if (!running.add(projectId)) {
            return UPDATING;
        }
        try {
            var changes = changes(projectId, deviceId, cancellation);
            if (changes.isEmpty()) {
                return FRESH;
            }
            if (changes.size() > MAX_CHANGES_BEFORE_SEARCH) {
                return UPDATING;
            }
            var dimensions = apply(projectId, deviceId, model, prefixes, changes, cancellation);
            store.finish(projectId, dimensions);
            return FRESH;
        } catch (IndexException | LlmException exception) {
            log.debug("Refreshing the index of project {} before a search failed", projectId, exception);
            return OFFLINE;
        } finally {
            running.remove(projectId);
        }
    }

    private Changes changes(UUID projectId, UUID deviceId, Cancellation cancellation) {
        var scanned = device.scan(projectId, deviceId, cancellation);
        var stored = store.hashes(projectId);
        var changed = scanned.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(stored.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        var removed = stored.keySet().stream()
                .filter(path -> !scanned.containsKey(path))
                .toList();
        return new Changes(changed, removed);
    }

    private Integer apply(
            UUID projectId,
            UUID deviceId,
            ResolvedModel model,
            EmbeddingPrefixes prefixes,
            Changes changes,
            Cancellation cancellation) {
        store.removeFiles(projectId, changes.removed());
        var changed = changes.changed();
        Integer dimensions = null;
        store.progress(projectId, changed.size(), 0);
        for (var start = 0; start < changed.size(); start += FILES_PER_REQUEST) {
            var paths = changed.subList(start, Math.min(start + FILES_PER_REQUEST, changed.size()));
            var files = device.chunks(projectId, deviceId, paths, cancellation);
            var texts = new ArrayList<String>();
            files.forEach(file -> file.chunks().forEach(chunk -> texts.add(chunk.embeddingText())));
            var vectors = texts.isEmpty()
                    ? List.<float[]>of()
                    : embedder.embedDocuments(model, prefixes, texts, cancellation);
            if (!vectors.isEmpty()) {
                dimensions = vectors.getFirst().length;
            }
            var offset = 0;
            for (var file : files) {
                if (file.gone()) {
                    store.removeFiles(projectId, List.of(file.path()));
                    continue;
                }
                var count = file.chunks().size();
                store.replaceFile(
                        projectId, file.path(), file.hash(), file.chunks(), vectors.subList(offset, offset + count));
                offset += count;
            }
            store.progress(projectId, changed.size(), start + paths.size());
        }
        return dimensions;
    }

    private record Changes(List<String> changed, List<String> removed) {

        boolean isEmpty() {
            return changed.isEmpty() && removed.isEmpty();
        }

        int size() {
            return changed.size() + removed.size();
        }
    }

    record SearchOutcome(List<IndexStore.SearchHit> hits, String freshness) {}
}
