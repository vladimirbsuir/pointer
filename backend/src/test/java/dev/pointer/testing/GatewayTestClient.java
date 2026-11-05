package dev.pointer.testing;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class GatewayTestClient implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    private final List<Consumer<JsonNode>> handlers = new CopyOnWriteArrayList<>();
    private volatile CloseStatus closeStatus;
    private final WebSocketSession session;

    private GatewayTestClient(int port, String accessToken) throws Exception {
        var headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(accessToken);
        session = new StandardWebSocketClient()
                .execute(
                        new TextWebSocketHandler() {
                            @Override
                            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                                var node = JSON.readTree(message.getPayload());
                                messages.add(node);
                                handlers.forEach(handler -> handler.accept(node));
                            }

                            @Override
                            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                                closeStatus = status;
                            }
                        },
                        headers,
                        URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
    }

    public static GatewayTestClient connect(int port, String accessToken, UUID deviceId, List<String> capabilities)
            throws Exception {
        return connect(port, accessToken, deviceId, capabilities, List.of());
    }

    public static GatewayTestClient connect(
            int port, String accessToken, UUID deviceId, List<String> capabilities, List<String> projects)
            throws Exception {
        return connect(port, accessToken, deviceId, capabilities, projects, List.of());
    }

    public static GatewayTestClient connect(
            int port,
            String accessToken,
            UUID deviceId,
            List<String> capabilities,
            List<String> projects,
            List<String> interpreters)
            throws Exception {
        var client = new GatewayTestClient(port, accessToken);
        client.send(
                "hello",
                Map.of(
                        "protocolVersion",
                        1,
                        "deviceId",
                        deviceId.toString(),
                        "deviceName",
                        "Test",
                        "clientVersion",
                        "test",
                        "capabilities",
                        capabilities,
                        "projects",
                        projects,
                        "interpreters",
                        interpreters));
        client.await(message -> type(message).equals("welcome"), Duration.ofSeconds(5));
        return client;
    }

    public static GatewayTestClient connectRaw(int port, String accessToken) throws Exception {
        return new GatewayTestClient(port, accessToken);
    }

    public void onMessage(Consumer<JsonNode> handler) {
        handlers.add(handler);
    }

    public void send(String type, Map<String, Object> payload) {
        try {
            session.sendMessage(new TextMessage(JSON.writeValueAsString(
                    Map.of("type", type, "id", UUID.randomUUID().toString(), "payload", payload))));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public void subscribe(UUID sessionId, long afterSeq) {
        send("session.subscribe", Map.of("sessionId", sessionId.toString(), "afterSeq", afterSeq));
    }

    public JsonNode await(Predicate<JsonNode> predicate, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (var message : messages) {
                if (predicate.test(message)) {
                    return message;
                }
            }
            sleep();
        }
        throw new AssertionError("Timed out waiting for message. Received: " + messages);
    }

    public JsonNode awaitEvent(String kind, Duration timeout) {
        return await(
                message -> type(message).equals("session.event")
                        && eventKind(message).equals(kind),
                timeout);
    }

    public List<JsonNode> messages(String type) {
        return messages.stream().filter(message -> type(message).equals(type)).toList();
    }

    public List<JsonNode> events() {
        return new ArrayList<>(messages("session.event"));
    }

    public CloseStatus awaitClose(Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (closeStatus == null && System.nanoTime() < deadline) {
            sleep();
        }
        return closeStatus;
    }

    public static String type(JsonNode message) {
        return message.path("type").asString("");
    }

    public static String eventKind(JsonNode message) {
        return message.path("payload").path("event").path("kind").asString("");
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws Exception {
        if (session.isOpen()) {
            session.close();
        }
    }
}
