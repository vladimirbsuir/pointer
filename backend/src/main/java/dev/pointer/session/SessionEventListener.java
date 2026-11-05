package dev.pointer.session;

import java.util.UUID;

public interface SessionEventListener {

    void onSessionEvent(UUID userId, SessionEventRecord event);
}
