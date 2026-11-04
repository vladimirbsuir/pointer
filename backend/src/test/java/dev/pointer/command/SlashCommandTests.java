package dev.pointer.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.IntegrationTest;
import dev.pointer.auth.AuthResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

class SlashCommandTests extends IntegrationTest {

    @Test
    void commandsAreCreatedListedUpdatedAndDeletedPerUser() {
        var auth = register(uniqueEmail());
        var other = register(uniqueEmail());

        var created = post(auth, Map.of("name", "review", "description", "Review", "prompt", "Review $ARGUMENTS"))
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        post(auth, Map.of("name", "review", "prompt", "Again")).expectStatus().isEqualTo(HttpStatus.CONFLICT);
        post(auth, Map.of("name", "Bad Name", "prompt", "x")).expectStatus().isBadRequest();

        var id = created.path("id").asString();
        client.put()
                .uri("/api/v1/commands/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("name", "review-pr", "prompt", "Review the PR"))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(list(auth)).singleElement().satisfies(command -> {
            assertThat(command.path("name").asString()).isEqualTo("review-pr");
            assertThat(command.path("prompt").asString()).isEqualTo("Review the PR");
            assertThat(command.path("description").asString()).isEmpty();
        });
        assertThat(list(other)).isEmpty();
        client.delete()
                .uri("/api/v1/commands/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer(other))
                .exchange()
                .expectStatus()
                .isNotFound();

        client.delete()
                .uri("/api/v1/commands/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(list(auth)).isEmpty();
    }

    private RestTestClient.ResponseSpec post(AuthResponse auth, Map<String, Object> body) {
        return client.post()
                .uri("/api/v1/commands")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(body)
                .exchange();
    }

    private JsonNode list(AuthResponse auth) {
        return client.get()
                .uri("/api/v1/commands")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }
}
