package dev.pointer.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.IntegrationTest;
import dev.pointer.auth.AuthResponse;
import dev.pointer.testing.FakeOpenAiServer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

class ProviderTests extends IntegrationTest {

    private final FakeOpenAiServer server = new FakeOpenAiServer();

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void createdProviderNeverExposesApiKey() {
        var auth = register(uniqueEmail());

        var provider = createProvider(auth, "sk-test-secret-1234");

        assertThat(provider.hasApiKey()).isTrue();
        assertThat(provider.apiKeyHint()).isEqualTo("1234");
        client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain("sk-test-secret"));
    }

    @Test
    void refreshDiscoversModelsUsingStoredKey() {
        server.withModels("model-b", "model-a").requireApiKey("sk-test-secret-1234");
        var auth = register(uniqueEmail());
        var provider = createProvider(auth, "sk-test-secret-1234");

        var refreshed = client.post()
                .uri("/api/v1/providers/{id}/models/refresh", provider.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ProviderDtos.ProviderResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(refreshed.models())
                .extracting(ProviderDtos.ModelResponse::modelKey)
                .containsExactly("model-a", "model-b");
        assertThat(refreshed.models()).allMatch(model -> model.source().equals("discovered"));
    }

    @Test
    void connectionTestReportsSuccessAndFailure() {
        server.requireApiKey("right-key");
        var auth = register(uniqueEmail());
        var good = createProvider(auth, "right-key");
        var bad = createProvider(auth, "wrong-key");

        testConnection(auth, good).jsonPath("$.ok").isEqualTo(true);
        testConnection(auth, bad)
                .jsonPath("$.ok")
                .isEqualTo(false)
                .jsonPath("$.errorCode")
                .isEqualTo("provider_unauthorized");
    }

    @Test
    void manualModelsCanBeAddedAndConfigured() {
        var auth = register(uniqueEmail());
        var provider = createProvider(auth, null);

        var model = client.post()
                .uri("/api/v1/providers/{id}/models", provider.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("modelKey", "custom-model", "contextWindow", 32000))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CREATED)
                .expectBody(ProviderDtos.ModelResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(model.source()).isEqualTo("manual");
        assertThat(model.contextWindow()).isEqualTo(32000);

        client.patch()
                .uri("/api/v1/providers/{id}/models/{modelId}", provider.id(), model.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("displayName", "Custom", "supportsTools", false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("Custom")
                .jsonPath("$.supportsTools")
                .isEqualTo(false);
    }

    @Test
    void defaultModelCanBeChosenOnlyFromOwnModels() {
        var owner = register(uniqueEmail());
        var other = register(uniqueEmail());
        var provider = createProvider(owner, null);
        var model = client.post()
                .uri("/api/v1/providers/{id}/models", provider.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .body(Map.of("modelKey", "m1"))
                .exchange()
                .expectBody(ProviderDtos.ModelResponse.class)
                .returnResult()
                .getResponseBody();

        client.put()
                .uri("/api/v1/me/settings")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .body(Map.of("defaultModelId", model.id().toString()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.defaultModelId")
                .isEqualTo(model.id().toString());

        client.put()
                .uri("/api/v1/me/settings")
                .header(HttpHeaders.AUTHORIZATION, bearer(other))
                .body(Map.of("defaultModelId", model.id().toString()))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void providersAreIsolatedBetweenUsers() {
        var owner = register(uniqueEmail());
        var other = register(uniqueEmail());
        var provider = createProvider(owner, null);

        client.delete()
                .uri("/api/v1/providers/{id}", provider.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(other))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    private ProviderDtos.ProviderResponse createProvider(AuthResponse auth, String apiKey) {
        var body = new HashMap<String, Object>(Map.of("name", "Fake", "baseUrl", server.baseUrl()));
        if (apiKey != null) {
            body.put("apiKey", apiKey);
        }
        return client.post()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(body)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CREATED)
                .expectBody(ProviderDtos.ProviderResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private RestTestClient.BodyContentSpec testConnection(AuthResponse auth, ProviderDtos.ProviderResponse provider) {
        return client.post()
                .uri("/api/v1/providers/{id}/test", provider.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }
}
