package dev.pointer.memory;

import dev.pointer.common.Ids;
import dev.pointer.common.error.ApiException;
import dev.pointer.project.ProjectService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class MemoryService {

    static final int MAX_DESCRIPTION = 200;
    static final int MAX_CONTENT = 4_000;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern SLUG_SEPARATOR = Pattern.compile("[^a-z0-9]+");
    private static final int MAX_QUERY_TERMS = 16;
    private static final int SLUG_WORDS = 6;

    private final MemoryStore store;
    private final MemoryEmbeddings embeddings;
    private final ProjectService projects;

    public List<Memory> list(UUID userId, UUID projectId, String status) {
        if (projectId != null) {
            projects.owned(userId, projectId);
        }
        return store.listScope(userId, projectId, status == null ? Memory.ACTIVE : status);
    }

    public List<Memory> recent(UUID userId, UUID projectId, int limit) {
        return store.recent(userId, projectId, limit);
    }

    @Transactional
    public Memory create(UUID userId, UUID projectId, Draft draft, String source, UUID deviceId) {
        if (projectId != null) {
            projects.owned(userId, projectId);
        }
        validate(draft);
        if (store.findByName(userId, projectId, draft.name()).isPresent()) {
            throw ApiException.conflict("memory_exists", "A memory with this name already exists");
        }
        var memory = insert(userId, projectId, draft, Memory.ACTIVE, source, null);
        embedLater(memory, deviceId);
        return memory;
    }

    @Transactional
    public Change save(UUID userId, UUID projectId, Draft draft, String source, UUID sessionId, UUID deviceId) {
        validate(draft);
        var existing = store.findByName(userId, projectId, draft.name());
        if (existing.isEmpty()) {
            var memory = insert(userId, projectId, draft, Memory.ACTIVE, source, sessionId);
            embedLater(memory, deviceId);
            return new Change(memory, Change.CREATED);
        }
        var previous = existing.get();
        var updated = withContent(previous, draft.kind(), draft.description(), draft.content(), previous.pinned());
        updated = withStatus(updated, Memory.ACTIVE);
        store.saveRevision(previous, previous.id());
        store.update(updated);
        embedLater(updated, deviceId);
        return new Change(updated, Change.UPDATED);
    }

    @Transactional
    public Memory suggest(UUID userId, UUID projectId, Draft draft, UUID sessionId) {
        validate(draft);
        if (store.findByName(userId, projectId, draft.name()).isPresent()) {
            return null;
        }
        return insert(userId, projectId, draft, Memory.SUGGESTED, "extracted", sessionId);
    }

    @Transactional
    public Memory update(UUID userId, UUID memoryId, Patch patch, UUID deviceId) {
        var previous = owned(userId, memoryId);
        var updated = withContent(
                previous,
                Objects.requireNonNullElse(patch.kind(), previous.kind()),
                Objects.requireNonNullElse(patch.description(), previous.description()),
                Objects.requireNonNullElse(patch.content(), previous.content()),
                Objects.requireNonNullElse(patch.pinned(), previous.pinned()));
        validate(new Draft(updated.kind(), updated.name(), updated.description(), updated.content()));
        store.saveRevision(previous, previous.id());
        store.update(updated);
        if (!updated.embeddingText().equals(previous.embeddingText())) {
            embedLater(updated, deviceId);
        }
        return updated;
    }

    @Transactional
    public Memory delete(UUID userId, UUID memoryId) {
        return changeStatus(owned(userId, memoryId), Memory.DELETED);
    }

    @Transactional
    public Change deleteByName(UUID userId, UUID projectId, String name) {
        var memory = store.findByName(userId, projectId, name)
                .filter(found -> Memory.ACTIVE.equals(found.status()))
                .orElseThrow(() -> ApiException.notFound("memory_not_found", "There is no memory named " + name));
        return new Change(changeStatus(memory, Memory.DELETED), Change.DELETED);
    }

    @Transactional
    public Memory accept(UUID userId, UUID memoryId, UUID deviceId) {
        var memory = owned(userId, memoryId);
        if (!Memory.SUGGESTED.equals(memory.status())) {
            throw ApiException.conflict("memory_not_suggested", "Only suggested memories can be accepted");
        }
        var accepted = changeStatus(memory, Memory.ACTIVE);
        embedLater(accepted, deviceId);
        return accepted;
    }

    @Transactional
    public Memory dismiss(UUID userId, UUID memoryId) {
        var memory = owned(userId, memoryId);
        if (!Memory.SUGGESTED.equals(memory.status())) {
            throw ApiException.conflict("memory_not_suggested", "Only suggested memories can be dismissed");
        }
        return changeStatus(memory, Memory.DISMISSED);
    }

    @Transactional
    public Memory undo(UUID userId, UUID memoryId) {
        var memory = owned(userId, memoryId);
        var revision = store.latestRevision(memoryId)
                .orElseThrow(() -> ApiException.conflict("nothing_to_undo", "This memory has no earlier version"));
        var restored = revision.status() == null
                ? withStatus(memory, Memory.DELETED)
                : withStatus(
                        withContent(
                                memory,
                                revision.kind(),
                                revision.description(),
                                revision.content(),
                                Boolean.TRUE.equals(revision.pinned())),
                        revision.status());
        if (Memory.ACTIVE.equals(restored.status())
                && !Memory.ACTIVE.equals(memory.status())
                && store.findByName(userId, memory.projectId(), memory.name()).isPresent()) {
            throw ApiException.conflict("memory_exists", "Another memory with this name exists now");
        }
        store.update(restored);
        store.deleteRevision(revision.id());
        return restored;
    }

    public List<Memory> read(UUID userId, UUID projectId, Collection<String> names) {
        var found = store.byNames(userId, projectId, names);
        store.markRead(found.stream().map(Memory::id).toList());
        return found;
    }

    public List<MemoryStore.ScoredMemory> search(UUID userId, UUID projectId, String query, int limit, UUID deviceId) {
        var merged = new LinkedHashMap<UUID, MemoryStore.ScoredMemory>();
        embeddings
                .query(userId, deviceId, query)
                .ifPresent(embedding -> store.vectorSearch(
                                userId, projectId, embedding.modelId(), embedding.vector(), limit)
                        .forEach(hit -> merged.put(hit.memory().id(), hit)));
        for (var hit : store.lexicalSearch(userId, projectId, tsQuery(query), limit)) {
            merged.putIfAbsent(hit.memory().id(), hit);
        }
        return merged.values().stream().limit(limit).toList();
    }

    public List<Memory> visible(UUID userId, UUID projectId) {
        return store.visible(userId, projectId);
    }

    @Transactional
    public Memory remember(UUID userId, UUID projectId, String text, String source) {
        if (projectId != null) {
            projects.owned(userId, projectId);
        }
        var content = text.strip();
        if (content.isEmpty()) {
            throw ApiException.badRequest("memory_empty", "Write what to remember");
        }
        var name = uniqueName(userId, projectId, slug(content));
        var description = content.lines().findFirst().orElse(content);
        if (description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION - 1) + "…";
        }
        var memory = insert(
                userId,
                projectId,
                new Draft("user", name, description, truncate(content)),
                Memory.ACTIVE,
                source,
                null);
        embedLater(memory, null);
        return memory;
    }

    static String tsQuery(String query) {
        var terms = new LinkedHashSet<String>();
        var matcher = WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < MAX_QUERY_TERMS) {
            if (matcher.group().length() > 1) {
                terms.add(matcher.group());
            }
        }
        return String.join(" | ", terms);
    }

    static String slug(String text) {
        var words = SLUG_SEPARATOR
                .splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isEmpty())
                .limit(SLUG_WORDS)
                .toList();
        var slug = String.join("-", words);
        if (slug.length() > 48) {
            slug = slug.substring(0, 48).replaceAll("-+$", "");
        }
        return slug.length() < 2 ? "note" : slug;
    }

    private String uniqueName(UUID userId, UUID projectId, String base) {
        var name = base;
        for (var suffix = 2; store.findByName(userId, projectId, name).isPresent(); suffix++) {
            name = base + "-" + suffix;
        }
        return name;
    }

    private Memory insert(UUID userId, UUID projectId, Draft draft, String status, String source, UUID sessionId) {
        var now = store.now();
        var memory = store.insert(new Memory(
                Ids.newId(),
                userId,
                projectId,
                draft.kind(),
                draft.name(),
                draft.description().strip(),
                draft.content().strip(),
                status,
                false,
                source,
                sessionId,
                0,
                null,
                now,
                now));
        store.saveRevision(null, memory.id());
        return memory;
    }

    private Memory changeStatus(Memory memory, String status) {
        store.saveRevision(memory, memory.id());
        var changed = withStatus(memory, status);
        store.update(changed);
        return changed;
    }

    private Memory owned(UUID userId, UUID memoryId) {
        return store.find(memoryId)
                .filter(memory -> memory.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("memory_not_found", "Memory does not exist"));
    }

    private void embedLater(Memory memory, UUID deviceId) {
        Runnable embed = () -> Thread.ofVirtual()
                .name("memory-embedding-" + memory.id())
                .start(() -> embeddings
                        .document(memory.userId(), deviceId, memory.embeddingText())
                        .ifPresent(embedding ->
                                store.saveEmbedding(memory.id(), embedding.modelId(), embedding.vector())));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    embed.run();
                }
            });
        } else {
            embed.run();
        }
    }

    private static void validate(Draft draft) {
        var errors = new ArrayList<String>();
        if (draft.kind() == null || !Memory.KINDS.contains(draft.kind())) {
            errors.add("kind must be one of user, feedback, project, reference");
        }
        if (draft.name() == null || !draft.name().matches(Memory.NAME_PATTERN)) {
            errors.add("name must be kebab-case, 2 to 64 characters");
        }
        if (draft.description() == null || draft.description().isBlank()) {
            errors.add("description is required");
        } else if (draft.description().length() > MAX_DESCRIPTION) {
            errors.add("description must be at most " + MAX_DESCRIPTION + " characters");
        }
        if (draft.content() == null || draft.content().isBlank()) {
            errors.add("content is required");
        } else if (draft.content().length() > MAX_CONTENT) {
            errors.add("content must be at most " + MAX_CONTENT + " characters");
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("invalid_memory", String.join("; ", errors));
        }
    }

    private static String truncate(String content) {
        return content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
    }

    private static Memory withContent(Memory memory, String kind, String description, String content, boolean pinned) {
        return new Memory(
                memory.id(),
                memory.userId(),
                memory.projectId(),
                kind,
                memory.name(),
                description.strip(),
                content.strip(),
                memory.status(),
                pinned,
                memory.source(),
                memory.sourceSessionId(),
                memory.readCount(),
                memory.lastReadAt(),
                memory.createdAt(),
                memory.updatedAt());
    }

    private static Memory withStatus(Memory memory, String status) {
        return new Memory(
                memory.id(),
                memory.userId(),
                memory.projectId(),
                memory.kind(),
                memory.name(),
                memory.description(),
                memory.content(),
                status,
                memory.pinned(),
                memory.source(),
                memory.sourceSessionId(),
                memory.readCount(),
                memory.lastReadAt(),
                memory.createdAt(),
                memory.updatedAt());
    }

    public record Draft(String kind, String name, String description, String content) {}

    public record Patch(String kind, String description, String content, Boolean pinned) {}

    public record Change(Memory memory, String action) {

        static final String CREATED = "created";
        static final String UPDATED = "updated";
        static final String DELETED = "deleted";

        Map<String, Object> toResult() {
            var result = new LinkedHashMap<String, Object>();
            result.put("memoryId", memory.id().toString());
            result.put("action", action);
            result.put("name", memory.name());
            result.put("scope", memory.scope());
            return result;
        }
    }
}
