package dev.pointer.rag;

import dev.pointer.common.error.ApiException;
import dev.pointer.project.ProjectService;
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

    private final IndexStore store;
    private final DeviceIndexClient device;
    private final Embedder embedder;
    private final SettingsService settings;
    private final ProjectService projects;
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
        return IndexDtos.IndexStatusResponse.of(store.find(projectId).orElse(null));
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
        if (!running.add(projectId)) {
            throw ApiException.conflict("index_running", "The project is already being indexed");
        }
        try {
            var previous = store.find(projectId).orElse(null);
            if (rebuild || previous == null || !modelId.equals(previous.embeddingModelId())) {
                store.clear(projectId);
            }
            store.begin(projectId, userId, modelId, model.modelKey());
        } catch (RuntimeException exception) {
            running.remove(projectId);
            throw exception;
        }
        Thread.ofVirtual().name("index-" + projectId).start(() -> {
            try {
                var cancellation = new Cancellation();
                var changes = changes(projectId, deviceId, cancellation);
                var dimensions = apply(projectId, deviceId, model, changes, cancellation);
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
        return IndexDtos.IndexStatusResponse.of(store.find(projectId).orElse(null));
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
        var freshness = refresh(projectId, deviceId, model, cancellation);
        var queryVector = embedder.embed(model, List.of(query), cancellation).getFirst();
        return new SearchOutcome(store.search(projectId, queryVector, pathPrefix, limit), freshness);
    }

    private String refresh(UUID projectId, UUID deviceId, ResolvedModel model, Cancellation cancellation) {
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
            var dimensions = apply(projectId, deviceId, model, changes, cancellation);
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
            UUID projectId, UUID deviceId, ResolvedModel model, Changes changes, Cancellation cancellation) {
        store.removeFiles(projectId, changes.removed());
        var changed = changes.changed();
        Integer dimensions = null;
        store.progress(projectId, changed.size(), 0);
        for (var start = 0; start < changed.size(); start += FILES_PER_REQUEST) {
            var paths = changed.subList(start, Math.min(start + FILES_PER_REQUEST, changed.size()));
            var files = device.chunks(projectId, deviceId, paths, cancellation);
            var texts = new ArrayList<String>();
            files.forEach(file -> file.chunks().forEach(chunk -> texts.add(chunk.embeddingText())));
            var vectors = texts.isEmpty() ? List.<float[]>of() : embedder.embed(model, texts, cancellation);
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
