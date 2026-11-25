package dev.pointer.container;

import dev.pointer.session.SessionEventListener;
import dev.pointer.session.SessionEventRecord;
import dev.pointer.session.SessionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;

@Component
@RequiredArgsConstructor
class DesktopViewHooks implements SessionEventListener {

    private static final CloseStatus SESSION_CLOSED = CloseStatus.GOING_AWAY.withReason("The session was closed");

    private final DesktopViewService service;

    @Override
    public void onSessionEvent(UUID userId, SessionEventRecord record) {
        var event = record.event();
        if ("session.updated".equals(event.get("kind")) && Boolean.TRUE.equals(event.get("archived"))) {
            service.closeViewers(record.sessionId(), SESSION_CLOSED);
        }
    }

    @TransactionalEventListener
    void onSessionDeleted(SessionService.SessionDeleted deleted) {
        service.closeViewers(deleted.sessionId(), SESSION_CLOSED);
    }
}
