package dev.pointer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class PasswordAuthTests extends IntegrationTest {

    @Test
    void registrationReturnsTokensAndProfile() {
        var email = uniqueEmail();

        var auth = register(email);

        assertThat(auth.accessToken()).isNotBlank();
        assertThat(auth.refreshToken()).isNotBlank();
        assertThat(auth.user().email()).isEqualTo(email);
        assertThat(auth.user().hasPassword()).isTrue();
        assertThat(auth.user().locale()).isEqualTo("en");
        assertThat(auth.user().github()).isNull();
    }

    @Test
    void registrationNormalizesEmailAndRejectsDuplicates() {
        var email = uniqueEmail();
        register(email);

        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "email",
                        email.toUpperCase(),
                        "password",
                        PASSWORD,
                        "displayName",
                        "Other",
                        "device",
                        device(UUID.randomUUID())))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("email_taken");
    }

    @Test
    void registrationValidatesInput() {
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "email",
                        "not-an-email",
                        "password",
                        "short",
                        "displayName",
                        "User",
                        "device",
                        device(UUID.randomUUID())))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("validation_failed")
                .jsonPath("$.fields.email")
                .exists()
                .jsonPath("$.fields.password")
                .exists();
    }

    @Test
    void loginSucceedsWithCorrectPasswordOnly() {
        var email = uniqueEmail();
        register(email);

        var auth = login(email, PASSWORD, UUID.randomUUID());
        assertThat(auth.user().email()).isEqualTo(email);

        client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", "wrong-password", "device", device(UUID.randomUUID())))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_credentials");
    }

    @Test
    void unknownEmailGetsSameErrorAsWrongPassword() {
        client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", uniqueEmail(), "password", PASSWORD, "device", device(UUID.randomUUID())))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_credentials");
    }

    @Test
    void refreshRotatesTokens() {
        var auth = register(uniqueEmail());

        var refreshed = refresh(auth.refreshToken())
                .expectStatus()
                .isOk()
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(refreshed.refreshToken()).isNotEqualTo(auth.refreshToken());
        assertThat(refreshed.user().id()).isEqualTo(auth.user().id());
    }

    @Test
    void reusingRotatedRefreshTokenRevokesWholeFamily() {
        var auth = register(uniqueEmail());
        var refreshed = refresh(auth.refreshToken())
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();

        refresh(auth.refreshToken())
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("refresh_token_reused");

        refresh(refreshed.refreshToken()).expectStatus().isUnauthorized();
    }

    @Test
    void concurrentRefreshWithSameTokenSucceedsAtMostOnce() throws Exception {
        var auth = register(uniqueEmail());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = IntStream.range(0, 4)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        return refresh(auth.refreshToken())
                                .returnResult(String.class)
                                .getStatus()
                                .value();
                    }))
                    .toList();
            start.countDown();
            var successes = 0;
            for (var attempt : attempts) {
                if (attempt.get() == 200) {
                    successes++;
                }
            }
            assertThat(successes).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void logoutRevokesRefreshToken() {
        var auth = register(uniqueEmail());

        client.post()
                .uri("/api/v1/auth/logout")
                .body(Map.of("refreshToken", auth.refreshToken()))
                .exchange()
                .expectStatus()
                .isNoContent();

        refresh(auth.refreshToken()).expectStatus().isUnauthorized();
    }

    @Test
    void protectedEndpointsRequireAccessToken() {
        client.get().uri("/api/v1/me").exchange().expectStatus().isUnauthorized();

        client.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void profileCanBeReadAndUpdated() {
        var auth = register(uniqueEmail());

        client.patch()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("displayName", "  Renamed  ", "locale", "ru"))
                .exchange()
                .expectStatus()
                .isOk();

        client.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("Renamed")
                .jsonPath("$.locale")
                .isEqualTo("ru");
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndRevokesOtherDevices() {
        var email = uniqueEmail();
        var auth = register(email);
        var otherDevice = login(email, PASSWORD, UUID.randomUUID());

        client.put()
                .uri("/api/v1/auth/password")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("currentPassword", "wrong-password", "newPassword", "new-password-123"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_current_password");

        client.put()
                .uri("/api/v1/auth/password")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("currentPassword", PASSWORD, "newPassword", "new-password-123"))
                .exchange()
                .expectStatus()
                .isNoContent();

        refresh(otherDevice.refreshToken()).expectStatus().isUnauthorized();
        refresh(auth.refreshToken()).expectStatus().isOk();
        login(email, "new-password-123", UUID.randomUUID());
    }

    @Test
    void signingInAnotherUserOnSameDeviceRevokesPreviousUsersTokens() {
        var deviceId = UUID.randomUUID();
        var first = register(uniqueEmail(), deviceId);

        register(uniqueEmail(), deviceId);

        refresh(first.refreshToken()).expectStatus().isUnauthorized();
    }
}
