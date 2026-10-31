package dev.pointer;

import dev.pointer.auth.AuthResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
public abstract class IntegrationTest {

    public static final String PASSWORD = "correct-horse-battery";

    @Autowired
    protected RestTestClient client;

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected static Map<String, Object> device(UUID id) {
        return Map.of("id", id.toString(), "name", "Test PC", "platform", Map.of("os", "windows"));
    }

    protected AuthResponse register(String email) {
        return register(email, UUID.randomUUID());
    }

    protected AuthResponse register(String email, UUID deviceId) {
        return client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "email", email, "password", PASSWORD, "displayName", "Test User", "device", device(deviceId)))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CREATED)
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected AuthResponse login(String email, String password, UUID deviceId) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password, "device", device(deviceId)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected RestTestClient.ResponseSpec refresh(String refreshToken) {
        return client.post()
                .uri("/api/v1/auth/refresh")
                .body(Map.of("refreshToken", refreshToken))
                .exchange();
    }

    protected static String bearer(AuthResponse auth) {
        return "Bearer " + auth.accessToken();
    }
}
