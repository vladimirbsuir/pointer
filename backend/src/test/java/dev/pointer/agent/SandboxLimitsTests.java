package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.session.SessionDtos;
import dev.pointer.testing.GatewayTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@TestPropertySource(
        properties = {
            "pointer.container.max-running=4",
            "pointer.container.sandbox-max-running-per-user=1",
            "pointer.container.idle-check-interval=200ms"
        })
class SandboxLimitsTests extends RemoteSessionIntegrationTest {

    private static final String COMMAND = "{\"command\":\"make\"}";

    @Test
    void aNewSandboxStopsTheUsersIdleSandboxAtTheLimit() throws Exception {
        var setup = setUp(false);
        enableSandbox(setup);
        var second = createSession(setup);
        autoApprove(setup, setup.sessionId());
        autoApprove(setup, second);

        try (var device = localDevice(setup)) {
            runSandboxTurn(setup, device, setup.sessionId());
            runSandboxTurn(setup, device, second);
        }

        var first = sandboxName(setup.sessionId());
        awaitTrue(() -> !fakeRuntime().isRunning(first));
        assertThat(sandboxStatus(setup, setup.sessionId()).path("status").asString())
                .isEqualTo("stopped");
        assertThat(sandboxStatus(setup, second).path("status").asString()).isEqualTo("ready");
        assertThat(toolItem(setup, second).path("toolStatus").asString()).isEqualTo("succeeded");
    }

    @Test
    void aNewSandboxFailsWhenTheUsersOtherSandboxIsBusy() throws Exception {
        var setup = setUp(false);
        enableSandbox(setup);
        var second = createSession(setup);
        autoApprove(setup, setup.sessionId());
        autoApprove(setup, second);

        try (var device = localDevice(setup)) {
            runSandboxTurn(setup, device, setup.sessionId());
            jdbc.sql("update sessions set status = 'running' where id = ?")
                    .param(setup.sessionId())
                    .update();
            llm.enqueueToolCall("call_b", "sandbox_run", COMMAND);
            llm.enqueueText("Could not run.");
            llm.enqueueText("Title");
            device.subscribe(second, 0);
            send(new Setup(setup.email(), setup.deviceId(), setup.auth(), setup.projectId(), second), "Build", null);
            awaitTurnCompleted(device, second);
        }

        var item = toolItem(setup, second);
        assertThat(item.path("toolStatus").asString()).isEqualTo("failed");
        assertThat(item.path("error").path("code").asString()).isEqualTo("executor_unavailable");
        assertThat(item.path("error").path("message").asString()).contains("the limit is 1 per user");
        assertThat(fakeRuntime().isRunning(sandboxName(setup.sessionId()))).isTrue();
        assertThat(fakeRuntime().createdNamed(sandboxName(second))).isEmpty();
        assertThat(sandboxStatus(setup, second).path("status").asString()).isEqualTo("failed");
    }

    @Test
    void aSandboxUnusedForLongerThanTheRetentionIsRemoved() throws Exception {
        var setup = setUp(false);
        enableSandbox(setup);
        autoApprove(setup, setup.sessionId());
        String deviceId;

        try (var device = localDevice(setup)) {
            deviceId = runSandboxTurn(setup, device, setup.sessionId());
        }
        jdbc.sql("update remote_workspaces set last_active_at = now() - interval '8 days' where session_id = ?")
                .param(setup.sessionId())
                .update();

        var name = sandboxName(setup.sessionId());
        awaitTrue(() -> fakeRuntime().removed().contains(name));
        awaitTrue(() -> sandboxStatus(setup, setup.sessionId())
                .path("status")
                .asString()
                .equals("none"));
        var revoked = jdbc.sql("select revoked_at is not null from devices where id = ?")
                .param(UUID.fromString(deviceId))
                .query(Boolean.class)
                .single();
        assertThat(revoked).isTrue();
    }

    private String runSandboxTurn(Setup setup, GatewayTestClient device, UUID sessionId) throws Exception {
        llm.enqueueToolCall("call_" + sessionId, "sandbox_run", COMMAND);
        llm.enqueueText("Built.");
        llm.enqueueText("Title");
        device.subscribe(sessionId, 0);
        send(new Setup(setup.email(), setup.deviceId(), setup.auth(), setup.projectId(), sessionId), "Build", null);
        var spec = fakeRuntime().awaitCreatedNamed(sandboxName(sessionId), TIMEOUT);
        awaitTrue(() -> fakeRuntime().isRunning(spec.name()));
        var runnerDevice = spec.environment().get("POINTER_DEVICE_ID");
        try (var runner = GatewayTestClient.connect(
                port, runnerToken(spec), UUID.fromString(runnerDevice), List.of("executor", "desktop"), List.of())) {
            runner.onMessage(message -> {
                if (!type(message).equals("exec.request")) {
                    return;
                }
                runner.send(
                        "exec.result",
                        Map.of(
                                "callId", message.path("payload").path("callId").asString(),
                                "status", "succeeded",
                                "result",
                                        result(message.path("payload")
                                                .path("operation")
                                                .asString())));
            });
            runner.send(
                    "device.projects_changed",
                    Map.of("projects", List.of(setup.projectId().toString())));
            awaitTurnCompleted(device, sessionId);
            device.await(
                    message -> eventKind(message).equals("session.updated")
                            && message.path("payload")
                                    .path("sessionId")
                                    .asString()
                                    .equals(sessionId.toString())
                            && message.path("payload")
                                    .path("event")
                                    .path("title")
                                    .asString()
                                    .equals("Title"),
                    TIMEOUT);
        }
        return runnerDevice;
    }

    private static void awaitTurnCompleted(GatewayTestClient device, UUID sessionId) {
        device.await(
                message -> eventKind(message).equals("turn.completed")
                        && message.path("payload").path("sessionId").asString().equals(sessionId.toString()),
                TIMEOUT);
    }

    private GatewayTestClient localDevice(Setup setup) throws Exception {
        var device = GatewayTestClient.connect(
                port,
                setup.auth().accessToken(),
                setup.deviceId(),
                List.of("viewer", "executor"),
                List.of(setup.projectId().toString()));
        device.onMessage(message -> {
            if (!type(message).equals("exec.request")) {
                return;
            }
            device.send(
                    "exec.result",
                    Map.of(
                            "callId", message.path("payload").path("callId").asString(),
                            "status", "succeeded",
                            "result", Map.of()));
        });
        return device;
    }

    private UUID createSession(Setup setup) {
        var modelId = client.get()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody()
                .modelId();
        return client.post()
                .uri("/api/v1/projects/{id}/sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelId", modelId.toString(), "mode", "agent"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CREATED)
                .expectBody(SessionDtos.SessionResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
    }

    private void enableSandbox(Setup setup) {
        client.patch()
                .uri("/api/v1/projects/{id}", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("sandboxEnabled", true))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private void autoApprove(Setup setup, UUID sessionId) {
        client.patch()
                .uri("/api/v1/sessions/{id}", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("autoApprove", true))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private JsonNode sandboxStatus(Setup setup, UUID sessionId) {
        return client.get()
                .uri("/api/v1/sessions/{id}/sandbox", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode toolItem(Setup setup, UUID sessionId) {
        var items = client.get()
                .uri("/api/v1/sessions/{id}/items", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        return StreamSupport.stream(items.spliterator(), false)
                .filter(item -> item.path("type").asString().equals("tool_call"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool call in session " + sessionId));
    }

    private static String sandboxName(UUID sessionId) {
        return "pointer-sandbox-" + sessionId;
    }
}
