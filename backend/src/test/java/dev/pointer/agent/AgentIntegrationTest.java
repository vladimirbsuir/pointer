package dev.pointer.agent;

import dev.pointer.IntegrationTest;
import dev.pointer.auth.AuthResponse;
import dev.pointer.provider.ProviderDtos;
import dev.pointer.session.SessionDtos;
import dev.pointer.testing.FakeOpenAiServer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

abstract class AgentIntegrationTest extends IntegrationTest {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Value("${local.server.port}")
    int port;

    final FakeOpenAiServer llm = new FakeOpenAiServer();

    @AfterEach
    void stopLlm() {
        llm.close();
    }

    RestTestClient.ResponseSpec send(Setup setup, String text, String idempotencyKey) {
        var request = client.post()
                .uri("/api/v1/sessions/{id}/messages", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.body(Map.of("text", text)).exchange();
    }

    String sessionStatus(Setup setup) {
        return client.get()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody()
                .status();
    }

    Setup setUp(boolean local) {
        return setUp(local, "agent");
    }

    Setup setUp(boolean local, String mode) {
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
                .body(Map.of("modelId", model.id().toString(), "mode", mode))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CREATED)
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody();
        return new Setup(
                email, deviceId, auth, UUID.fromString(project.path("id").asString()), session.id());
    }

    record Setup(String email, UUID deviceId, AuthResponse auth, UUID projectId, UUID sessionId) {}
}
