package dev.pointer.gateway;

import dev.pointer.common.Ids;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.HttpTransport;
import dev.pointer.provider.llm.LlmException;
import dev.pointer.provider.llm.RelayTransports;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
class RelayService implements RelayTransports {

    static final String RELAY = "relay";
    private static final long START_TIMEOUT_SECONDS = 60;
    private static final long CHUNK_TIMEOUT_SECONDS = 300;
    private static final long POLL_MILLIS = 250;

    private final ConnectionRegistry registry;
    private final Map<UUID, RelayCall> calls = new ConcurrentHashMap<>();

    RelayService(ConnectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public HttpTransport forUser(UUID userId, UUID preferredDeviceId) {
        return (request, cancellation) -> send(pickConnection(userId, preferredDeviceId), request, cancellation);
    }

    void onStart(UUID relayId, int status) {
        var call = calls.get(relayId);
        if (call != null) {
            call.status.complete(status);
        }
    }

    void onChunk(UUID relayId, String base64) {
        var call = calls.get(relayId);
        if (call != null) {
            call.chunks.offer(Base64.getDecoder().decode(base64));
        }
    }

    void onEnd(UUID relayId) {
        var call = calls.get(relayId);
        if (call != null) {
            call.finish(null);
        }
    }

    void onError(UUID relayId, String code, String message) {
        var call = calls.get(relayId);
        if (call != null) {
            call.finish(new IOException(code + ": " + message));
        }
    }

    void onDisconnect(GatewayConnection connection) {
        calls.values().stream()
                .filter(call -> call.connection == connection)
                .forEach(call -> call.finish(new IOException("Device disconnected")));
    }

    private GatewayConnection pickConnection(UUID userId, UUID preferredDeviceId) {
        return registry.device(preferredDeviceId)
                .filter(connection -> connection.userId().equals(userId) && connection.hasCapability(RELAY))
                .or(() -> registry.forUser(userId).stream()
                        .filter(connection -> connection.hasCapability(RELAY))
                        .findFirst())
                .orElseThrow(() -> new LlmException(
                        "local_provider_offline", "Open Pointer on the computer running the local model", 0));
    }

    private HttpTransport.StreamResponse send(
            GatewayConnection connection, HttpTransport.Request request, Cancellation cancellation) throws IOException {
        var call = new RelayCall(Ids.newId(), connection);
        calls.put(call.id, call);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("relayId", call.id);
        payload.put("method", request.method());
        payload.put("url", request.url());
        payload.put("headers", request.headers());
        payload.put("body", request.body() == null ? null : Base64.getEncoder().encodeToString(request.body()));
        connection.send("relay.request", null, payload);
        try {
            int status = awaitStatus(call, cancellation);
            return new HttpTransport.StreamResponse(status, new RelayInputStream(call), () -> release(call));
        } catch (ExecutionException exception) {
            release(call);
            throw new IOException(exception.getCause().getMessage(), exception.getCause());
        } catch (TimeoutException exception) {
            release(call);
            throw new IOException("Local model did not respond", exception);
        } catch (IOException exception) {
            release(call);
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            release(call);
            throw new IOException("Relay interrupted", exception);
        }
    }

    private static int awaitStatus(RelayCall call, Cancellation cancellation)
            throws ExecutionException, InterruptedException, TimeoutException, IOException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
        while (true) {
            try {
                return call.status.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                if (cancellation.isCancelled()) {
                    throw new IOException("Request cancelled");
                }
                if (System.nanoTime() > deadline) {
                    throw exception;
                }
            }
        }
    }

    private void release(RelayCall call) {
        if (calls.remove(call.id) != null && !call.finished.get()) {
            call.connection.send("relay.cancel", null, Map.of("relayId", call.id));
            call.finish(new IOException("Relay cancelled"));
        }
    }

    private static final class RelayCall {

        private static final byte[] END = new byte[0];

        final UUID id;
        final GatewayConnection connection;
        final CompletableFuture<Integer> status = new CompletableFuture<>();
        final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        final AtomicBoolean finished = new AtomicBoolean();
        volatile IOException failure;

        RelayCall(UUID id, GatewayConnection connection) {
            this.id = id;
            this.connection = connection;
        }

        void finish(IOException error) {
            if (finished.compareAndSet(false, true)) {
                failure = error;
                if (error != null) {
                    status.completeExceptionally(error);
                }
                chunks.offer(END);
            }
        }
    }

    private static final class RelayInputStream extends InputStream {

        private final RelayCall call;
        private byte[] current = new byte[0];
        private int offset;
        private boolean ended;

        RelayInputStream(RelayCall call) {
            this.call = call;
        }

        @Override
        public int read() throws IOException {
            var single = new byte[1];
            var count = read(single, 0, 1);
            return count < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            while (offset >= current.length) {
                if (ended) {
                    return -1;
                }
                try {
                    var next = call.chunks.poll(CHUNK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (next == null) {
                        throw new IOException("Local model stopped responding");
                    }
                    if (next == RelayCall.END) {
                        ended = true;
                        if (call.failure != null) {
                            throw call.failure;
                        }
                        return -1;
                    }
                    current = next;
                    offset = 0;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Relay interrupted", exception);
                }
            }
            var count = Math.min(len, current.length - offset);
            System.arraycopy(current, offset, buffer, off, count);
            offset += count;
            return count;
        }
    }
}
