package dev.pointer.device;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.IntegrationTest;
import dev.pointer.auth.AuthResponse;
import dev.pointer.testing.GatewayTestClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

class DeviceTests extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int ACCESS_REVOKED = 4403;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void listsAndRenamesTheUsersDevices() {
        var email = uniqueEmail();
        var laptopId = UUID.randomUUID();
        var laptop = register(email, laptopId);
        var desktopId = UUID.randomUUID();
        login(email, PASSWORD, desktopId);

        var devices = devices(laptop);
        assertThat(devices.findValues("id").stream().map(JsonNode::asString))
                .containsExactlyInAnyOrder(laptopId.toString(), desktopId.toString());
        var current = devices.valueStream()
                .filter(device -> device.path("current").asBoolean())
                .toList();
        assertThat(current)
                .singleElement()
                .satisfies(device -> assertThat(device.path("id").asString()).isEqualTo(laptopId.toString()));

        client.patch()
                .uri("/api/v1/devices/{id}", desktopId)
                .header(HttpHeaders.AUTHORIZATION, bearer(laptop))
                .body(Map.of("name", "Office PC"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("Office PC");
        login(email, PASSWORD, desktopId);

        assertThat(devices(laptop)
                        .valueStream()
                        .filter(device -> device.path("id").asString().equals(desktopId.toString()))
                        .map(device -> device.path("name").asString()))
                .containsExactly("Office PC");
    }

    @Test
    void revokingADeviceClosesItsConnectionAndEndsItsSession() throws Exception {
        var email = uniqueEmail();
        var laptop = register(email, UUID.randomUUID());
        var desktopId = UUID.randomUUID();
        var desktop = login(email, PASSWORD, desktopId);

        try (var connection =
                GatewayTestClient.connect(port, desktop.accessToken(), desktopId, List.of("viewer", "executor"))) {
            revoke(laptop, desktopId).expectStatus().isNoContent();

            assertThat(connection.awaitClose(TIMEOUT).getCode()).isEqualTo(ACCESS_REVOKED);
        }
        client.post()
                .uri("/api/v1/auth/refresh")
                .body(Map.of("refreshToken", desktop.refreshToken()))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("device_revoked");
        try (var again = GatewayTestClient.connectRaw(port, desktop.accessToken())) {
            assertThat(again.awaitClose(TIMEOUT).getCode()).isEqualTo(ACCESS_REVOKED);
        }
        assertThat(devices(laptop).findValues("id").stream().map(JsonNode::asString))
                .doesNotContain(desktopId.toString());

        var signedInAgain = login(email, PASSWORD, desktopId);
        try (var restored =
                GatewayTestClient.connect(port, signedInAgain.accessToken(), desktopId, List.of("viewer"))) {
            assertThat(restored.awaitClose(Duration.ofMillis(500))).isNull();
        }
    }

    @Test
    void anotherUsersDeviceCannotBeRevoked() {
        var owner = register(uniqueEmail());
        var otherDeviceId = UUID.randomUUID();
        register(uniqueEmail(), otherDeviceId);

        revoke(owner, otherDeviceId)
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("device_not_found");
    }

    @Test
    void disabledAccountIsRefusedByTheGateway() throws Exception {
        var auth = register(uniqueEmail());
        jdbc.update(
                "update users set disabled_at = now() where id = ?", auth.user().id());

        try (var connection = GatewayTestClient.connectRaw(port, auth.accessToken())) {
            assertThat(connection.awaitClose(TIMEOUT).getCode()).isEqualTo(ACCESS_REVOKED);
        }
    }

    private JsonNode devices(AuthResponse auth) {
        return client.get()
                .uri("/api/v1/devices")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private RestTestClient.ResponseSpec revoke(AuthResponse auth, UUID deviceId) {
        return client.delete()
                .uri("/api/v1/devices/{id}", deviceId)
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .exchange();
    }
}
