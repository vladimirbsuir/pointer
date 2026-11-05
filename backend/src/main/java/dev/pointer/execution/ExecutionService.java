package dev.pointer.execution;

import dev.pointer.common.Ids;
import dev.pointer.provider.llm.Cancellation;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionService {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration DISCONNECT_GRACE = Duration.ofSeconds(30);
    private static final Duration CANCEL_GRACE = Duration.ofSeconds(5);

    private final DeviceChannel channel;
    private final ObjectProvider<ServerOperation> serverOperations;
    private final Map<UUID, PendingCall> pending = new ConcurrentHashMap<>();

    public ExecutionResult execute(ExecutionRequest request, ExecutionListener listener, Cancellation cancellation) {
        var serverOperation = serverOperation(request.operation());
        if (serverOperation.isPresent()) {
            return serverOperation.get().execute(request, cancellation);
        }
        if (!awaitDevice(request, listener, cancellation)) {
            return ExecutionResult.failure(
                    ExecutionResult.CANCELLED, "cancelled", "Cancelled while waiting for the device");
        }
        var callId = Ids.newId();
        var call = new PendingCall(request.deviceId(), listener);
        pending.put(callId, call);
        try {
            channel.sendRequest(request.deviceId(), payload(callId, request));
            return awaitResult(callId, call, request, cancellation);
        } finally {
            pending.remove(callId);
        }
    }

    public boolean canExecute(UUID deviceId, UUID projectId) {
        return channel.canExecute(deviceId, projectId);
    }

    public Optional<UUID> executorFor(UUID userId, UUID projectId) {
        return channel.executorFor(userId, projectId);
    }

    public Optional<Set<String>> interpreters(UUID deviceId) {
        return deviceId == null ? Optional.empty() : channel.interpreters(deviceId);
    }

    public boolean isOperationAvailable(String operation, UUID projectId) {
        return serverOperation(operation)
                .map(server -> projectId == null || server.isAvailable(projectId))
                .orElse(true);
    }

    private Optional<ServerOperation> serverOperation(String operation) {
        return serverOperations.stream()
                .filter(server -> server.operation().equals(operation))
                .findFirst();
    }

    public void onOutput(UUID callId, String stream, String data) {
        var call = pending.get(callId);
        if (call != null) {
            call.listener.onOutput(stream, data);
        }
    }

    public void onResult(UUID callId, ExecutionResult result) {
        var call = pending.get(callId);
        if (call != null) {
            call.future.complete(result);
        }
    }

    private boolean awaitDevice(ExecutionRequest request, ExecutionListener listener, Cancellation cancellation) {
        var waiting = false;
        while (!channel.canExecute(request.deviceId(), request.projectId())) {
            if (cancellation.isCancelled()) {
                return false;
            }
            if (!waiting) {
                waiting = true;
                listener.onWaitingForDevice();
            }
            sleep(POLL_INTERVAL);
        }
        if (waiting) {
            listener.onDeviceAvailable();
        }
        return true;
    }

    private ExecutionResult awaitResult(
            UUID callId, PendingCall call, ExecutionRequest request, Cancellation cancellation) {
        var deadline =
                System.nanoTime() + request.timeout().plus(DISCONNECT_GRACE).toNanos();
        var cancelSentAt = 0L;
        while (true) {
            try {
                return call.future.get(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                if (cancellation.isCancelled() && cancelSentAt == 0) {
                    channel.sendCancel(call.deviceId, callId);
                    cancelSentAt = System.nanoTime();
                }
                if (cancelSentAt != 0 && System.nanoTime() - cancelSentAt > CANCEL_GRACE.toNanos()) {
                    return ExecutionResult.failure(ExecutionResult.CANCELLED, "cancelled", "Cancelled");
                }
                if (System.nanoTime() > deadline) {
                    return channel.isConnected(call.deviceId)
                            ? ExecutionResult.failure(
                                    ExecutionResult.TIMEOUT, "timeout", "The device did not respond in time")
                            : ExecutionResult.failure(
                                    ExecutionResult.FAILED,
                                    "executor_disconnected",
                                    "The device disconnected; the outcome of the operation is unknown");
                }
            } catch (ExecutionException exception) {
                return ExecutionResult.failure(ExecutionResult.FAILED, "executor_error", exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ExecutionResult.failure(ExecutionResult.CANCELLED, "cancelled", "Interrupted");
            }
        }
    }

    private static Map<String, Object> payload(UUID callId, ExecutionRequest request) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("callId", callId);
        payload.put("sessionId", request.sessionId());
        payload.put("projectId", request.projectId());
        payload.put("operation", request.operation());
        payload.put("arguments", request.arguments());
        payload.put("dryRun", request.dryRun());
        payload.put("timeoutMs", request.timeout().toMillis());
        if (request.worktree() != null) {
            payload.put("worktree", request.worktree());
        }
        return payload;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PendingCall {
        final UUID deviceId;
        final ExecutionListener listener;
        final CompletableFuture<ExecutionResult> future = new CompletableFuture<>();

        PendingCall(UUID deviceId, ExecutionListener listener) {
            this.deviceId = deviceId;
            this.listener = listener;
        }
    }

    public static ExecutionResult parseResult(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        var result =
                payload.get("result") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.<String, Object>of();
        @SuppressWarnings("unchecked")
        var error = payload.get("error") instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        @SuppressWarnings("unchecked")
        var changes = payload.get("fileChanges") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.<Map<String, Object>>of();
        return new ExecutionResult(
                String.valueOf(payload.getOrDefault("status", ExecutionResult.FAILED)), result, error, changes);
    }
}
