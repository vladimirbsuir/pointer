package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.IntegrationTest;
import dev.pointer.auth.AuthResponse;
import dev.pointer.provider.ProviderDtos;
import dev.pointer.session.SessionDtos;
import dev.pointer.testing.FakeOpenAiServer;
import dev.pointer.testing.GatewayTestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

class AgentFlowTests extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Value("${local.server.port}")
    private int port;

    private final FakeOpenAiServer llm = new FakeOpenAiServer();

    @AfterEach
    void stopServer() {
        llm.close();
    }

    @Test
    void messageStreamsAnswerThroughGatewayAndGeneratesTitle() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("Hel", "lo!");
        llm.enqueueText("Greeting");

        try (var ws = GatewayTestClient.connect(port, setup.auth.accessToken(), setup.deviceId, List.of("viewer"))) {
            ws.subscribe(setup.sessionId, 0);
            ws.await(message -> GatewayTestClient.type(message).equals("session.subscribed"), TIMEOUT);

            send(setup, "Say hello", null).expectStatus().isEqualTo(HttpStatus.ACCEPTED);

            var completed = ws.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("completed");
            var deltas = ws.messages("session.delta").stream()
                    .map(message -> message.path("payload").path("delta").asString())
                    .toList();
            assertThat(String.join("", deltas)).isEqualTo("Hello!");
            var assistant = ws.events().stream()
                    .filter(message -> eventKind(message).equals("item.completed"))
                    .map(message -> message.path("payload").path("event").path("item"))
                    .filter(item -> item.path("type").asString().equals("assistant_message"))
                    .findFirst()
                    .orElseThrow();
            assertThat(assistant.path("text").asString()).isEqualTo("Hello!");
            assertThat(assistant.path("status").asString()).isEqualTo("completed");
            ws.awaitEvent("usage.updated", TIMEOUT);

            var titled = ws.await(
                    message -> eventKind(message).equals("session.updated")
                            && message.path("payload")
                                    .path("event")
                                    .path("title")
                                    .asString()
                                    .equals("Greeting"),
                    TIMEOUT);
            assertThat(titled).isNotNull();
            assertContiguous(ws.events());
        }

        assertThat(llm.requests().getFirst()).contains("Say hello").contains("You are Pointer");
    }

    @Test
    void titleGenerationDoesNotOverwriteFinishedTurnStatus() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("First answer");
        llm.enqueueText("Slow title");
        llm.enqueueText("Second answer");

        try (var ws = GatewayTestClient.connect(port, setup.auth.accessToken(), setup.deviceId, List.of("viewer"))) {
            ws.subscribe(setup.sessionId, 0);
            send(setup, "First", null);
            ws.await(
                    message -> eventKind(message).equals("session.updated")
                            && message.path("payload")
                                    .path("event")
                                    .path("title")
                                    .asString()
                                    .equals("Slow title"),
                    TIMEOUT);

            assertThat(sessionStatus(setup)).isEqualTo("idle");
            var second = send(setup, "Second", null)
                    .expectBody(SessionDtos.SendMessageResponse.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(second.queued()).isFalse();
            ws.await(
                    message -> ws.events().stream()
                                    .filter(event -> eventKind(event).equals("turn.completed"))
                                    .count()
                            == 2,
                    TIMEOUT);
        }
    }

    @Test
    void lateSubscriberReceivesFullHistoryInOrder() throws Exception {
        var setup = setUp(false);
        llm.enqueueText("Answer");

        try (var first = GatewayTestClient.connect(port, setup.auth.accessToken(), setup.deviceId, List.of("viewer"))) {
            first.subscribe(setup.sessionId, 0);
            send(setup, "Question", null);
            first.awaitEvent("turn.completed", TIMEOUT);
        }

        var otherDevice = UUID.randomUUID();
        var otherAuth = login(setup.email, PASSWORD, otherDevice);
        try (var late = GatewayTestClient.connect(port, otherAuth.accessToken(), otherDevice, List.of("viewer"))) {
            late.subscribe(setup.sessionId, 0);
            late.awaitEvent("turn.completed", TIMEOUT);
            var events = late.events();
            assertContiguous(events);
            assertThat(events.getFirst().path("payload").path("seq").asLong()).isEqualTo(1);
            assertThat(events.stream().map(GatewayTestClient::eventKind))
                    .contains("turn.started", "item.created", "item.completed", "turn.completed");
        }
    }

    @Test
    void cancellationStopsTheTurn() throws Exception {
        var setup = setUp(false);
        var lines = new String[200];
        for (var i = 0; i < lines.length; i++) {
            lines[i] = "{\"choices\":[{\"delta\":{\"content\":\"word \"}}]}";
        }
        llm.enqueueStream(Duration.ofMillis(50), lines);

        try (var ws = GatewayTestClient.connect(port, setup.auth.accessToken(), setup.deviceId, List.of("viewer"))) {
            ws.subscribe(setup.sessionId, 0);
            send(setup, "Write a long text", null);
            ws.await(message -> GatewayTestClient.type(message).equals("session.delta"), TIMEOUT);

            client.post()
                    .uri("/api/v1/sessions/{id}/cancel", setup.sessionId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.ACCEPTED);

            var completed = ws.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("cancelled");
        }
        assertThat(sessionStatus(setup)).isEqualTo("idle");
    }

    @Test
    void providerErrorFailsTurnAndSession() throws Exception {
        var setup = setUp(false);
        llm.enqueueError(400, "{\"error\":{\"message\":\"context length exceeded\"}}");

        try (var ws = GatewayTestClient.connect(port, setup.auth.accessToken(), setup.deviceId, List.of("viewer"))) {
            ws.subscribe(setup.sessionId, 0);
            send(setup, "Hi", null);

            var completed =
                    ws.awaitEvent("turn.completed", TIMEOUT).path("payload").path("event");
            assertThat(completed.path("reason").asString()).isEqualTo("failed");
            assertThat(completed.path("error").path("code").asString()).isEqualTo("provider_error");
            assertThat(completed.path("error").path("message").asString()).isEqualTo("context length exceeded");
        }
        assertThat(sessionStatus(setup)).isEqualTo("error");
    }

    @Test
    void messageSentDuringTurnIsQueuedAndRunsNext() throws Exception {
        var setup = setUp(false);
        llm.enqueueStream(
                Duration.ofMillis(100),
                "{\"choices\":[{\"delta\":{\"content\":\"first \"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}",
                "[DONE]");
        llm.enqueueText("Title");
        llm.enqueueText("second answer");

        try (var ws = GatewayTestClient.connect(port, setup.auth.accessToken(), setup.deviceId, List.of("viewer"))) {
            ws.subscribe(setup.sessionId, 0);
            send(setup, "First", null);
            ws.await(message -> GatewayTestClient.type(message).equals("session.delta"), TIMEOUT);

            var queued = send(setup, "Second", null)
                    .expectBody(SessionDtos.SendMessageResponse.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(queued.queued()).isTrue();

            ws.await(
                    message -> ws.events().stream()
                                    .filter(event -> eventKind(event).equals("turn.completed"))
                                    .count()
                            == 2,
                    TIMEOUT);
        }
        assertThat(llm.requests().stream().filter(request -> request.contains("Second")))
                .isNotEmpty();
    }

    @Test
    void idempotencyKeyPreventsDuplicateTurns() {
        var setup = setUp(false);
        llm.enqueueText("Once");

        var first = send(setup, "Hello", "key-1")
                .expectBody(SessionDtos.SendMessageResponse.class)
                .returnResult()
                .getResponseBody();
        var second = send(setup, "Hello", "key-1")
                .expectBody(SessionDtos.SendMessageResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(second.turnId()).isEqualTo(first.turnId());
    }

    @Test
    void localProviderIsReachedThroughDeviceRelay() throws Exception {
        var setup = setUp(true);
        llm.enqueueText("from ", "local");
        var http = HttpClient.newHttpClient();
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        try (var ws =
                GatewayTestClient.connect(port, setup.auth.accessToken(), setup.deviceId, List.of("viewer", "relay"))) {
            ws.onMessage(message -> {
                if (GatewayTestClient.type(message).equals("relay.request")) {
                    executor.submit(() -> relay(ws, http, message.path("payload")));
                }
            });
            ws.subscribe(setup.sessionId, 0);
            send(setup, "Hi local", null);

            var completed = ws.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("completed");
            var deltas = ws.messages("session.delta").stream()
                    .map(message -> message.path("payload").path("delta").asString())
                    .toList();
            assertThat(String.join("", deltas)).isEqualTo("from local");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void localProviderWithoutConnectedDeviceFailsClearly() throws Exception {
        var setup = setUp(true);

        try (var ws = GatewayTestClient.connect(port, setup.auth.accessToken(), setup.deviceId, List.of("viewer"))) {
            ws.subscribe(setup.sessionId, 0);
            send(setup, "Hi", null);

            var completed =
                    ws.awaitEvent("turn.completed", TIMEOUT).path("payload").path("event");
            assertThat(completed.path("error").path("code").asString()).isEqualTo("local_provider_offline");
        }
    }

    @Test
    void subscribingToForeignSessionIsRejected() throws Exception {
        var setup = setUp(false);
        var strangerDevice = UUID.randomUUID();
        var stranger = register(uniqueEmail(), strangerDevice);

        try (var ws = GatewayTestClient.connect(port, stranger.accessToken(), strangerDevice, List.of("viewer"))) {
            ws.subscribe(setup.sessionId, 0);
            var error = ws.await(message -> GatewayTestClient.type(message).equals("error"), TIMEOUT);
            assertThat(error.path("payload").path("code").asString()).isEqualTo("session_not_found");
        }
    }

    @Test
    void helloWithAnotherDeviceIdIsRejected() throws Exception {
        var setup = setUp(false);

        try (var ws = GatewayTestClient.connectRaw(port, setup.auth.accessToken())) {
            ws.send(
                    "hello",
                    Map.of(
                            "protocolVersion", 1,
                            "deviceId", UUID.randomUUID().toString(),
                            "capabilities", List.of("viewer"),
                            "projects", List.of()));
            assertThat(ws.awaitClose(TIMEOUT).getCode()).isEqualTo(4400);
        }
    }

    private static void relay(GatewayTestClient ws, HttpClient http, JsonNode request) {
        var relayId = request.path("relayId").asString();
        try {
            var builder = HttpRequest.newBuilder(URI.create(request.path("url").asString()));
            request.path("headers")
                    .properties()
                    .forEach(entry ->
                            builder.header(entry.getKey(), entry.getValue().asString()));
            var body = request.path("body").isString()
                    ? Base64.getDecoder().decode(request.path("body").asString())
                    : new byte[0];
            builder.method(request.path("method").asString(), HttpRequest.BodyPublishers.ofByteArray(body));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            ws.send(
                    "relay.response.start",
                    Map.of("relayId", relayId, "status", response.statusCode(), "headers", Map.of()));
            try (var stream = response.body()) {
                var buffer = new byte[1024];
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    var chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    ws.send(
                            "relay.response.chunk",
                            Map.of(
                                    "relayId",
                                    relayId,
                                    "data",
                                    Base64.getEncoder().encodeToString(chunk)));
                }
            }
            ws.send("relay.response.end", Map.of("relayId", relayId));
        } catch (Exception exception) {
            ws.send("relay.error", Map.of("relayId", relayId, "code", "connection_failed", "message", "" + exception));
        }
    }

    private static void assertContiguous(List<JsonNode> events) {
        var seqs = events.stream()
                .map(event -> event.path("payload").path("seq").asLong())
                .toList();
        for (var i = 1; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(seqs.get(i - 1) + 1);
        }
    }

    private RestTestClient.ResponseSpec send(Setup setup, String text, String idempotencyKey) {
        var request = client.post()
                .uri("/api/v1/sessions/{id}/messages", setup.sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.body(Map.of("text", text)).exchange();
    }

    private String sessionStatus(Setup setup) {
        return client.get()
                .uri("/api/v1/sessions/{id}", setup.sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth))
                .exchange()
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody()
                .status();
    }

    private Setup setUp(boolean local) {
        var email = uniqueEmail();
        var deviceId = UUID.randomUUID();
        var auth = register(email, deviceId);
        var providerBody = new HashMap<String, Object>(Map.of("name", "Fake", "baseUrl", llm.baseUrl()));
        providerBody.put("isLocal", local);
        var provider = client.post()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(providerBody)
                .exchange()
                .expectBody(ProviderDtos.ProviderResponse.class)
                .returnResult()
                .getResponseBody();
        var model = client.post()
                .uri("/api/v1/providers/{id}/models", provider.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("modelKey", "fake-model", "contextWindow", 8000))
                .exchange()
                .expectBody(ProviderDtos.ModelResponse.class)
                .returnResult()
                .getResponseBody();
        var project = client.post()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("name", "demo"))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        var session = client.post()
                .uri("/api/v1/projects/{id}/sessions", project.path("id").asString())
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("modelId", model.id().toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CREATED)
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody();
        return new Setup(email, deviceId, auth, session.id());
    }

    private record Setup(String email, UUID deviceId, AuthResponse auth, UUID sessionId) {}
}
