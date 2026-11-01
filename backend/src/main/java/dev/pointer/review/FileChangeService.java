package dev.pointer.review;

import dev.pointer.session.SessionEventPublisher;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileChangeService {

    private static final int MAX_STORED_CHARS = 1_000_000;

    private final FileChangeRepository changes;
    private final SessionEventPublisher events;
    private final Clock clock;

    FileChangeService(FileChangeRepository changes, SessionEventPublisher events, Clock clock) {
        this.changes = changes;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void record(UUID userId, UUID sessionId, UUID turnId, UUID itemId, List<Map<String, Object>> fileChanges) {
        for (var change : fileChanges) {
            var saved = changes.save(new FileChange(
                    sessionId,
                    turnId,
                    itemId,
                    String.valueOf(change.get("path")),
                    String.valueOf(change.getOrDefault("changeKind", "modified")),
                    limited(change.get("before")),
                    limited(change.get("after")),
                    number(change.get("linesAdded")),
                    number(change.get("linesRemoved")),
                    clock.instant()));
            var payload = new LinkedHashMap<String, Object>();
            payload.put("changeId", saved.getId());
            payload.put("itemId", itemId);
            payload.put("path", saved.getPath());
            payload.put("changeKind", saved.getChangeKind());
            payload.put("linesAdded", saved.getLinesAdded());
            payload.put("linesRemoved", saved.getLinesRemoved());
            events.append(userId, sessionId, "file.changed", payload);
        }
    }

    private static String limited(Object content) {
        if (content == null) {
            return null;
        }
        var text = String.valueOf(content);
        return text.length() > MAX_STORED_CHARS ? null : text;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

interface FileChangeRepository extends JpaRepository<FileChange, UUID> {}
