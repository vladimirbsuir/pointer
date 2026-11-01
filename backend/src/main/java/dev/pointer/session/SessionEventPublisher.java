package dev.pointer.session;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SessionEventPublisher {

    private final SessionEventLog eventLog;

    SessionEventPublisher(SessionEventLog eventLog) {
        this.eventLog = eventLog;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID userId, UUID sessionId, String kind, Map<String, Object> payload) {
        eventLog.append(userId, sessionId, kind, payload);
    }
}
