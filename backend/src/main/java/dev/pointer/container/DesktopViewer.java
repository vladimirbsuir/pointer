package dev.pointer.container;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

final class DesktopViewer implements WebSocket.Listener {

    static final CloseStatus TOO_SLOW = new CloseStatus(4408, "Too slow");
    static final CloseStatus TOO_MANY_VIEWERS = new CloseStatus(4429, "Too many viewers");
    static final CloseStatus DISPLAY_UNREACHABLE = new CloseStatus(4503, "The display is not reachable");
    static final CloseStatus DISPLAY_STOPPED = CloseStatus.GOING_AWAY.withReason("The display is not running");
    static final String BINARY_PROTOCOL = "binary";

    private static final Logger log = LoggerFactory.getLogger(DesktopViewer.class);
    private static final String BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final long POLL_MILLIS = 500;
    private static final long INPUT_BUFFER_LIMIT = 1024 * 1024;

    private final WebSocketSession viewer;
    private final UUID sessionId;
    private final DesktopViewers viewers;
    private final long bufferLimit;
    private final BlockingQueue<BinaryMessage> outbound = new LinkedBlockingQueue<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong pendingInputBytes = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<WebSocket> connected = new CompletableFuture<>();
    private CompletableFuture<WebSocket> upstreamSends = connected;

    DesktopViewer(
            WebSocketSession viewer, UUID sessionId, DesktopViewers viewers, long bufferLimit, Duration sendTimeout) {
        this.viewer = viewer;
        this.sessionId = sessionId;
        this.viewers = viewers;
        this.bufferLimit = bufferLimit;
        limitBlockingSends(viewer, sendTimeout);
    }

    UUID sessionId() {
        return sessionId;
    }

    void connect(HttpClient client, URI address) {
        var builder = client.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (BINARY_PROTOCOL.equals(viewer.getAcceptedProtocol())) {
            builder.subprotocols(BINARY_PROTOCOL);
        }
        builder.buildAsync(address, this).whenComplete((upstream, failure) -> {
            if (failure != null) {
                log.debug("The display stream of session {} is not reachable: {}", sessionId, failure.getMessage());
                connected.completeExceptionally(failure);
                close(DISPLAY_UNREACHABLE);
                return;
            }
            connected.complete(upstream);
            if (closed.get()) {
                upstream.abort();
            }
        });
        Thread.ofVirtual().name("desktop-view-" + sessionId).start(this::drain);
    }

    void forward(BinaryMessage message) {
        if (closed.get()) {
            return;
        }
        var payload = message.getPayload();
        var size = payload.remaining();
        if (pendingInputBytes.addAndGet(size) > INPUT_BUFFER_LIMIT) {
            close(TOO_SLOW);
            return;
        }
        var copy = ByteBuffer.allocate(size).put(payload).flip();
        var last = message.isLast();
        synchronized (this) {
            upstreamSends = upstreamSends
                    .thenCompose(upstream -> upstream.sendBinary(copy, last))
                    .whenComplete((upstream, failure) -> afterInputSent(size, failure));
        }
    }

    private void afterInputSent(int size, Throwable failure) {
        if (failure != null) {
            close(DISPLAY_STOPPED);
            return;
        }
        pendingInputBytes.addAndGet(-size);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        var bytes = new byte[data.remaining()];
        data.get(bytes);
        if (queuedBytes.addAndGet(bytes.length) > bufferLimit) {
            close(TOO_SLOW);
            return null;
        }
        outbound.add(new BinaryMessage(bytes, last));
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        close(DISPLAY_STOPPED);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        close(DISPLAY_STOPPED);
    }

    void close(CloseStatus status) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        viewers.unregister(sessionId, this);
        outbound.clear();
        connected.thenAccept(WebSocket::abort);
        Thread.ofVirtual().name("desktop-view-close-" + sessionId).start(() -> {
            try {
                if (viewer.isOpen()) {
                    viewer.close(status);
                }
            } catch (IOException | RuntimeException exception) {
                log.debug("Could not close a desktop viewer of session {}: {}", sessionId, exception.getMessage());
            }
        });
    }

    private void drain() {
        while (!closed.get()) {
            try {
                var message = outbound.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                viewer.sendMessage(message);
                queuedBytes.addAndGet(-message.getPayloadLength());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                close(DISPLAY_STOPPED);
            } catch (IOException | RuntimeException exception) {
                close(TOO_SLOW);
            }
        }
    }

    private static void limitBlockingSends(WebSocketSession session, Duration timeout) {
        if (session instanceof NativeWebSocketSession nativeSession) {
            var standard = nativeSession.getNativeSession(jakarta.websocket.Session.class);
            if (standard != null) {
                standard.getUserProperties().put(BLOCKING_SEND_TIMEOUT, timeout.toMillis());
            }
        }
    }
}
