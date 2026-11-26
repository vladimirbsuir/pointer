package dev.pointer.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

class GatewayKeepAliveTests extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ConnectionRegistry registry;

    @Test
    void serverPingsIdleConnectionsAndRecordsPongs() throws Exception {
        var auth = register(uniqueEmail());
        var pinged = new CountDownLatch(1);
        var connectedAt = Instant.now();
        var socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + auth.accessToken())
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                        pinged.countDown();
                        return WebSocket.Listener.super.onPing(webSocket, message);
                    }
                })
                .get(5, TimeUnit.SECONDS);
        try {
            assertThat(pinged.await(25, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitPongAfter(connectedAt, Duration.ofSeconds(5))).isTrue();
        } finally {
            socket.abort();
        }
    }

    private boolean awaitPongAfter(Instant connectedAt, Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (registry.all().stream()
                    .anyMatch(connection -> connection.lastPongAt().isAfter(connectedAt))) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
