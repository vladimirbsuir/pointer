package dev.pointer.container;

import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutorReadiness;
import dev.pointer.session.SessionEventListener;
import dev.pointer.session.SessionEventRecord;
import dev.pointer.session.SessionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
class RemoteSessionHooks implements ExecutorReadiness, SessionEventListener {

    private final RemoteSessionService service;

    @Override
    public boolean manages(UUID deviceId) {
        return service.manages(deviceId);
    }

    @Override
    public boolean isReady(ExecutionRequest request) {
        return service.isReady(request);
    }

    @Override
    public void wake(ExecutionRequest request) {
        service.wake(request);
    }

    @Override
    public void onSessionEvent(UUID userId, SessionEventRecord record) {
        var kind = String.valueOf(record.event().get("kind"));
        switch (kind) {
            case "turn.started" -> service.touch(record.sessionId());
            case "turn.completed" -> {
                var turnId = record.event().get("turnId");
                if (turnId != null) {
                    service.onTurnCompleted(userId, record.sessionId(), UUID.fromString(String.valueOf(turnId)));
                }
            }
            default -> {}
        }
    }

    @TransactionalEventListener
    void onSessionDeleted(SessionService.SessionDeleted deleted) {
        service.onSessionDeleted(deleted.sessionId(), deleted.deviceId());
    }

    @EventListener(ApplicationReadyEvent.class)
    void resetInterruptedStarts() {
        service.resetInterruptedStarts();
    }

    @Scheduled(fixedDelayString = "${pointer.container.idle-check-interval:60s}")
    void stopIdle() {
        if (service.isEnabled()) {
            service.stopIdle();
        }
    }
}
