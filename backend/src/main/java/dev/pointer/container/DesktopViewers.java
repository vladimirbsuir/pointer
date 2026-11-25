package dev.pointer.container;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.socket.CloseStatus;

class DesktopViewers {

    private final Map<UUID, Set<DesktopViewer>> viewers = new ConcurrentHashMap<>();

    synchronized boolean register(UUID sessionId, DesktopViewer viewer, int limit) {
        var open = viewers.computeIfAbsent(sessionId, id -> ConcurrentHashMap.newKeySet());
        if (open.size() >= limit) {
            return false;
        }
        open.add(viewer);
        return true;
    }

    synchronized void unregister(UUID sessionId, DesktopViewer viewer) {
        var open = viewers.get(sessionId);
        if (open == null) {
            return;
        }
        open.remove(viewer);
        if (open.isEmpty()) {
            viewers.remove(sessionId);
        }
    }

    int count(UUID sessionId) {
        var open = viewers.get(sessionId);
        return open == null ? 0 : open.size();
    }

    void closeAll(UUID sessionId, CloseStatus status) {
        var open = viewers.get(sessionId);
        if (open != null) {
            List.copyOf(open).forEach(viewer -> viewer.close(status));
        }
    }
}
