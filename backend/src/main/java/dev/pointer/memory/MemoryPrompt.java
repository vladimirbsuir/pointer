package dev.pointer.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemoryPrompt {

    static final int BUDGET_CHARS = 6_000;
    static final int MAX_PINNED_CHARS = 1_500;
    private static final String INSTRUCTIONS = """
            Memory:
            Facts saved in earlier sessions with memory_save. The index shows names and descriptions; read a memory \
            with memory_read before relying on its details. Memories can be outdated: verify file paths, symbols and \
            commands they mention before acting on them, and update or delete a memory that turned out to be wrong.
            Save a memory when the user states a preference or corrects you, or when you learn a non-obvious fact \
            about the project that is not in the code or the project rules. Do not save what the repository already \
            records or what only matters to the current task.""";

    private final MemoryService memories;
    private final MemoryEmbeddings embeddings;
    private final MemoryStore store;

    public String section(UUID userId, UUID projectId, String query, UUID deviceId) {
        var visible = memories.visible(userId, projectId);
        var pinned = visible.stream().filter(Memory::pinned).toList();
        var indexed = new ArrayList<>(
                visible.stream().filter(memory -> !memory.pinned()).toList());
        var text = new StringBuilder(INSTRUCTIONS);
        if (visible.isEmpty()) {
            return text.append("\nNo memories are saved yet.").toString();
        }
        for (var memory : pinned) {
            text.append("\n\nPinned ").append(header(memory)).append('\n').append(clip(memory.content()));
        }
        var lines = indexed.stream().map(MemoryPrompt::indexLine).toList();
        var room = BUDGET_CHARS - text.length();
        if (lines.stream().mapToInt(line -> line.length() + 1).sum() > room && query != null && !query.isBlank()) {
            indexed = new ArrayList<>(byRelevance(userId, projectId, indexed, query, deviceId));
            lines = indexed.stream().map(MemoryPrompt::indexLine).toList();
        }
        if (!lines.isEmpty()) {
            text.append("\n\nIndex:");
        }
        var shown = 0;
        for (var line : lines) {
            if (text.length() + line.length() + 1 > BUDGET_CHARS) {
                break;
            }
            text.append('\n').append(line);
            shown++;
        }
        if (shown < lines.size()) {
            text.append("\n")
                    .append(lines.size() - shown)
                    .append(" more memories are not listed; find them with memory_search.");
        }
        return text.toString();
    }

    private List<Memory> byRelevance(UUID userId, UUID projectId, List<Memory> indexed, String query, UUID deviceId) {
        var embedding = embeddings.query(userId, deviceId, query);
        if (embedding.isEmpty()) {
            return indexed;
        }
        var scores = new HashMap<UUID, Double>();
        store.vectorSearch(
                        userId,
                        projectId,
                        embedding.get().modelId(),
                        embedding.get().vector(),
                        indexed.size())
                .forEach(hit -> scores.put(hit.memory().id(), hit.score()));
        return indexed.stream()
                .sorted(Comparator.comparingDouble((Memory memory) -> scores.getOrDefault(memory.id(), -1.0))
                        .reversed())
                .toList();
    }

    private static String header(Memory memory) {
        return "[" + memory.kind() + ", " + memory.scope() + "] " + memory.name() + ": " + memory.description();
    }

    private static String indexLine(Memory memory) {
        return "- " + header(memory);
    }

    private static String clip(String content) {
        return content.length() > MAX_PINNED_CHARS ? content.substring(0, MAX_PINNED_CHARS) + "…" : content;
    }
}
