package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.testing.GatewayTestClient;
import dev.pointer.testing.RawWebSocket;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

class DesktopViewTests extends RemoteSessionIntegrationTest {

    private static final Map<String, Object> STOPPED = Map.of("running", false, "stream", false);
    private static final Map<String, Object> RUNNING =
            Map.of("running", true, "stream", true, "width", 1280, "height", 800);

    private final AtomicReference<Map<String, Object>> display = new AtomicReference<>(STOPPED);
    private RawWebSocket.Server websockify;

    @AfterEach
    void stopWebsockify() throws Exception {
        if (websockify != null) {
            websockify.close();
        }
    }

    @Test
    void statusAndTicketsFollowTheDisplayAndBelongToTheOwner() throws Exception {
        var setup = remoteSetUp();
        var sessionId = createSession(setup);
        var name = "pointer-session-" + sessionId;
        awaitTrue(() -> !fakeRuntime().createdNamed(name).isEmpty());
        var spec = fakeRuntime().createdNamed(name).getFirst();
        var full = spec.environment().get("POINTER_VNC_PASSWORD");
        var view = spec.environment().get("POINTER_VNC_VIEW_PASSWORD");
        assertThat(full).matches("[A-Za-z0-9]{8}");
        assertThat(view).matches("[A-Za-z0-9]{8}").isNotEqualTo(full);

        try (var runner = desktopRunner(setup)) {
            awaitReady(setup, sessionId);
            var stopped = status(setup, sessionId);
            assertThat(stopped.path("available").asBoolean()).isTrue();
            assertThat(stopped.path("running").asBoolean()).isFalse();
            assertThat(ticketRequest(setup, sessionId).getStatus().value()).isEqualTo(409);

            display.set(RUNNING);
            var running = status(setup, sessionId);
            assertThat(running.path("running").asBoolean()).isTrue();
            assertThat(running.path("width").asInt()).isEqualTo(1280);
            var ticket = ticket(setup, sessionId);
            assertThat(ticket.path("password").asString()).isEqualTo(view);
            assertThat(ticket.path("path").asString()).isEqualTo("/api/v1/sessions/" + sessionId + "/desktop/stream");
            assertThat(ticket.path("ticket").asString()).hasSizeGreaterThan(40);
            assertThat(ticket.path("height").asInt()).isEqualTo(800);

            var stranger = setUp(false);
            client.get()
                    .uri("/api/v1/sessions/{id}/desktop", sessionId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(stranger.auth()))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
            client.post()
                    .uri("/api/v1/sessions/{id}/desktop/ticket", sessionId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(stranger.auth()))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }
    }

    @Test
    void aWorkspaceWithoutAViewSecretOrADesktopIsNotAvailable() throws Exception {
        var setup = remoteSetUp();
        var sessionId = createSession(setup);
        try (var runner = startRunner(setup, new CopyOnWriteArrayList<>())) {
            awaitReady(setup, sessionId);
            assertThat(status(setup, sessionId).path("available").asBoolean()).isFalse();
        }

        jdbc.sql("update remote_workspaces set status = 'stopped' where session_id = ?")
                .param(sessionId)
                .update();
        var other = remoteSetUp();
        var second = createSession(other);
        display.set(RUNNING);
        try (var runner = desktopRunner(other)) {
            awaitReady(other, second);
            assertThat(status(other, second).path("available").asBoolean()).isTrue();
            jdbc.sql("update remote_workspaces set desktop_view_secret = null where session_id = ?")
                    .param(second)
                    .update();
            assertThat(status(other, second).path("available").asBoolean()).isFalse();
            assertThat(ticketRequest(other, second).getStatus().value()).isEqualTo(409);
        }
    }

    @Test
    void theRelayForwardsTheStreamBothWaysWithSingleUseTickets() throws Exception {
        var setup = remoteSetUp();
        var sessionId = createSession(setup);
        useWebsockify();
        display.set(RUNNING);
        try (var runner = desktopRunner(setup)) {
            awaitReady(setup, sessionId);
            var ticket = ticket(setup, sessionId).path("ticket").asString();

            try (var viewer = RawWebSocket.connect(port, streamPath(sessionId, ticket), "binary", null)) {
                assertThat(viewer.status()).isEqualTo(101);
                assertThat(viewer.header("Sec-WebSocket-Protocol")).isEqualTo("binary");
                assertThat(viewer.read().payload()).isEqualTo(RawWebSocket.Server.GREETING);
                assertThat(websockify.protocols()).containsExactly("binary");

                var update = new byte[2 * 1024 * 1024];
                update[update.length - 1] = 7;
                websockify.sendToAll(update);
                var relayed = viewer.read();
                assertThat(relayed.opcode()).isEqualTo(RawWebSocket.BINARY);
                assertThat(relayed.payload()).isEqualTo(update);

                var input = new byte[100_000];
                input[0] = 5;
                viewer.sendBinary(input);
                var received = new java.io.ByteArrayOutputStream();
                while (received.size() < input.length) {
                    received.write(websockify.awaitReceived());
                }
                assertThat(received.toByteArray()).isEqualTo(input);

                try (var reused = RawWebSocket.connect(port, streamPath(sessionId, ticket), "binary", null)) {
                    assertThat(reused.status()).isEqualTo(403);
                }
                var other = ticket(setup, sessionId).path("ticket").asString();
                try (var wrongSession =
                        RawWebSocket.connect(port, streamPath(UUID.randomUUID(), other), "binary", null)) {
                    assertThat(wrongSession.status()).isEqualTo(403);
                }
                var foreign = ticket(setup, sessionId).path("ticket").asString();
                try (var wrongOrigin =
                        RawWebSocket.connect(port, streamPath(sessionId, foreign), "binary", "https://evil.example")) {
                    assertThat(wrongOrigin.status()).isEqualTo(403);
                }

                websockify.dropConnections();
                assertThat(viewer.closeCode()).isEqualTo(1001);
            }
        }
    }

    @Test
    void theViewerLimitHoldsAndASlowViewerIsDroppedAlone() throws Exception {
        var setup = remoteSetUp();
        var sessionId = createSession(setup);
        useWebsockify();
        display.set(RUNNING);
        try (var runner = desktopRunner(setup)) {
            awaitReady(setup, sessionId);
            var first = ticket(setup, sessionId).path("ticket").asString();
            var second = ticket(setup, sessionId).path("ticket").asString();
            var third = ticket(setup, sessionId).path("ticket").asString();

            try (var slow = RawWebSocket.connect(port, streamPath(sessionId, first), null, null);
                    var fast = RawWebSocket.connect(port, streamPath(sessionId, second), null, null)) {
                assertThat(fast.read().payload()).isEqualTo(RawWebSocket.Server.GREETING);
                awaitTrue(() -> websockify.connectionCount() == 2);
                assertThat(ticketRequest(setup, sessionId).getStatus().value()).isEqualTo(429);
                try (var extra = RawWebSocket.connect(port, streamPath(sessionId, third), null, null)) {
                    assertThat(extra.status()).isEqualTo(101);
                    assertThat(extra.closeCode()).isEqualTo(4429);
                }

                var fastBytes = new AtomicLong();
                var marker = new AtomicBoolean();
                Thread.ofVirtual().start(() -> {
                    try {
                        while (true) {
                            var frame = fast.read();
                            fastBytes.addAndGet(frame.payload().length);
                            if (frame.payload().length == 3) {
                                marker.set(true);
                            }
                        }
                    } catch (Exception exception) {
                        marker.compareAndSet(false, false);
                    }
                });
                var chunk = new byte[256 * 1024];
                for (var sent = 0; sent < 400 && websockify.connectionCount() == 2; sent++) {
                    websockify.sendToAllQuietly(chunk);
                }
                awaitTrue(() -> websockify.connectionCount() == 1);
                websockify.sendToAll(new byte[] {1, 2, 3});
                awaitTrue(marker::get);
                assertThat(fastBytes.get()).isGreaterThan(4L * 1024 * 1024);
            }
        }
    }

    @Test
    void deletingTheSessionClosesItsViewers() throws Exception {
        var setup = remoteSetUp();
        var sessionId = createSession(setup);
        useWebsockify();
        display.set(RUNNING);
        try (var runner = desktopRunner(setup)) {
            awaitReady(setup, sessionId);
            var ticket = ticket(setup, sessionId).path("ticket").asString();
            try (var viewer = RawWebSocket.connect(port, streamPath(sessionId, ticket), null, null)) {
                assertThat(viewer.read().payload()).isEqualTo(RawWebSocket.Server.GREETING);
                client.delete()
                        .uri("/api/v1/sessions/{id}", sessionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                        .exchange()
                        .expectStatus()
                        .isNoContent();
                assertThat(viewer.closeCode()).isEqualTo(1001);
            }
        }
    }

    private void useWebsockify() throws Exception {
        websockify = new RawWebSocket.Server();
        fakeRuntime().useStreamAddress(URI.create("ws://127.0.0.1:" + websockify.port() + "/"));
    }

    private GatewayTestClient desktopRunner(Setup setup) throws Exception {
        return startRunner(setup, new CopyOnWriteArrayList<>(), List.of("executor", "desktop"), operation -> {
            if (operation.equals("pointer.desktop_status")) {
                return display.get();
            }
            return result(operation);
        });
    }

    private UUID createSession(Setup setup) {
        return UUID.fromString(
                createRemoteSession(setup).path("session").path("id").asString());
    }

    private void awaitReady(Setup setup, UUID sessionId) {
        awaitWorkspace(
                setup,
                sessionId,
                workspace -> "ready".equals(workspace.path("status").asString()));
    }

    private JsonNode status(Setup setup, UUID sessionId) {
        return client.get()
                .uri("/api/v1/sessions/{id}/desktop", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private org.springframework.test.web.servlet.client.EntityExchangeResult<JsonNode> ticketRequest(
            Setup setup, UUID sessionId) {
        return client.post()
                .uri("/api/v1/sessions/{id}/desktop/ticket", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult();
    }

    private JsonNode ticket(Setup setup, UUID sessionId) {
        var result = ticketRequest(setup, sessionId);
        assertThat(result.getStatus().value())
                .as("ticket %s", result.getResponseBody())
                .isEqualTo(200);
        return result.getResponseBody();
    }

    private static String streamPath(UUID sessionId, String ticket) {
        return "/api/v1/sessions/" + sessionId + "/desktop/stream?ticket=" + ticket;
    }
}
