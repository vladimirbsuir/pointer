package dev.pointer;

import org.junit.jupiter.api.Test;

class PointerApplicationTests extends IntegrationTest {

    @Test
    void healthEndpointReportsUp() {
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void openApiDocumentIsServed() {
        client.get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.openapi")
                .exists();
    }
}
