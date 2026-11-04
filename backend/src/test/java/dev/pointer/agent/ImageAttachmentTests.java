package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.testing.GatewayTestClient;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

class ImageAttachmentTests extends AgentIntegrationTest {

    private static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @Test
    void imageIsUploadedAndSentToVisionModelAsDataUrl() throws Exception {
        var setup = setUp(false);
        setVision(setup, true);
        llm.enqueueText("A single pixel.");
        llm.enqueueText("Pixel");
        var attachmentId = upload(setup, MediaType.IMAGE_PNG)
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("id")
                .asString();

        try (var ws =
                GatewayTestClient.connect(port, setup.auth().accessToken(), setup.deviceId(), List.of("viewer"))) {
            ws.subscribe(setup.sessionId(), 0);
            sendWithImage(setup, attachmentId).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
            ws.awaitEvent("turn.completed", TIMEOUT);
        }

        assertThat(llm.requests().getFirst())
                .contains("\"type\":\"image_url\"")
                .contains("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG));
        var downloaded = client.get()
                .uri("/api/v1/sessions/{id}/attachments/{attachmentId}", setup.sessionId(), attachmentId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.IMAGE_PNG)
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        assertThat(downloaded).isEqualTo(PNG);
    }

    @Test
    void imagesAreRejectedForModelsWithoutVisionAndForOtherTypes() {
        var setup = setUp(false);
        var attachmentId = upload(setup, MediaType.IMAGE_PNG)
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("id")
                .asString();

        sendWithImage(setup, attachmentId)
                .expectStatus()
                .isBadRequest()
                .expectBody(JsonNode.class)
                .value(body -> assertThat(body.toString()).contains("model_without_vision"));
        upload(setup, MediaType.APPLICATION_PDF).expectStatus().is4xxClientError();
    }

    private RestTestClient.ResponseSpec upload(Setup setup, MediaType type) {
        return client.post()
                .uri("/api/v1/sessions/{id}/attachments", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .contentType(type)
                .body(PNG)
                .exchange();
    }

    private RestTestClient.ResponseSpec sendWithImage(Setup setup, String attachmentId) {
        return client.post()
                .uri("/api/v1/sessions/{id}/messages", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of(
                        "text",
                        "What is this?",
                        "attachments",
                        List.of(Map.of("kind", "image", "attachmentId", attachmentId))))
                .exchange();
    }

    private void setVision(Setup setup, boolean supportsVision) {
        var provider = client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .get(0);
        client.patch()
                .uri(
                        "/api/v1/providers/{id}/models/{modelId}",
                        provider.path("id").asString(),
                        provider.path("models").get(0).path("id").asString())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("supportsVision", supportsVision))
                .exchange()
                .expectStatus()
                .isOk();
    }
}
