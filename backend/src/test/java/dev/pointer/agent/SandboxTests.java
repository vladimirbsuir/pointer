package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.testing.GatewayTestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SandboxTests extends RemoteSessionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SANDBOX_IMAGE = "pointer-runner:gui";

    @Test
    void desktopToolsAndSandboxRunOfALocalSessionRunInItsSandboxWhileOtherToolsStayLocal() throws Exception {
        var setup = setUp(false);
        enableVision(setup);
        enableSandbox(setup, true);
        autoApprove(setup);
        llm.enqueueToolCall("call_1", "desktop_start", "{}");
        llm.enqueueToolCall("call_2", "sandbox_run", "{\"command\":\"apt-get install -y xeyes\"}");
        llm.enqueueToolCall("call_3", "run_command", "{\"command\":\"dir\"}");
        llm.enqueueText("Done.");
        llm.enqueueText("Title");
        var local = new CopyOnWriteArrayList<JsonNode>();
        var sandbox = new CopyOnWriteArrayList<JsonNode>();

        try (var device = localDevice(setup, local)) {
            send(setup, "Start the display", null);
            try (var runner =
                    startRunner(setup, sandbox, List.of("executor", "desktop"), SandboxTests::sandboxResult)) {
                device.awaitEvent("turn.completed", TIMEOUT);
            }
        }

        var spec = fakeRuntime()
                .createdNamed("pointer-sandbox-" + setup.sessionId())
                .getFirst();
        assertThat(spec.image()).isEqualTo(SANDBOX_IMAGE);
        assertThat(spec.volume()).isEqualTo("pointer-sandbox-" + setup.sessionId());
        assertThat(spec.environment()).containsEntry("POINTER_WORKSPACE", "/workspace/project");
        assertThat(operations(sandbox)).contains("desktop_start", "run_command");
        assertThat(operations(local)).contains("run_command").doesNotContain("desktop_start");
        var sandboxCommand = executed(sandbox, "run_command").getFirst();
        assertThat(sandboxCommand.path("arguments").path("command").asString()).isEqualTo("apt-get install -y xeyes");
        assertThat(sandboxCommand.has("worktree")).isFalse();
        assertThat(executed(local, "run_command")
                        .getFirst()
                        .path("arguments")
                        .path("command")
                        .asString())
                .isEqualTo("dir");

        var firstRequest = JSON.readTree(llm.requests().getFirst());
        assertThat(description(firstRequest, "desktop_start")).contains("Linux sandbox container");
        assertThat(description(firstRequest, "desktop_launch"))
                .contains("sandbox_run")
                .doesNotContain("run_command");
        assertThat(description(firstRequest, "sandbox_run")).contains("run_command runs on the user's computer");
        assertThat(toolItem(setup, "sandbox_run").path("toolStatus").asString()).isEqualTo("succeeded");

        client.get()
                .uri("/api/v1/projects/{id}/remote-sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);
        client.get()
                .uri("/api/v1/sessions/{id}/remote", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isNotFound();

        client.delete()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isNoContent();
        awaitTrue(() -> fakeRuntime().removed().contains(spec.name()));
        awaitTrue(() -> client.post()
                .uri("/api/v1/runner/token")
                .body(Map.of(
                        "deviceId",
                        spec.environment().get("POINTER_DEVICE_ID"),
                        "secret",
                        spec.environment().get("POINTER_RUNNER_SECRET")))
                .exchange()
                .returnResult()
                .getStatus()
                .equals(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void aProjectWithoutTheSandboxOffersNeitherDesktopNorSandboxTools() throws Exception {
        var setup = setUp(false);
        enableVision(setup);
        llm.enqueueText("Nothing to do.");
        llm.enqueueText("Title");

        try (var device = localDevice(setup, new CopyOnWriteArrayList<>())) {
            send(setup, "Hello", null);
            device.awaitEvent("turn.completed", TIMEOUT);
        }

        var names = toolNames(JSON.readTree(llm.requests().getFirst()));
        assertThat(names).contains("run_command").doesNotContain("desktop_start", "sandbox_run");
        assertThat(fakeRuntime().createdNamed("pointer-sandbox-" + setup.sessionId()))
                .isEmpty();
    }

    @Test
    void aSandboxThatCannotStartFailsTheCallInsteadOfWaiting() throws Exception {
        var setup = setUp(false);
        enableVision(setup);
        enableSandbox(setup, true);
        autoApprove(setup);
        fakeRuntime().failCreatesOf("pointer-sandbox-" + setup.sessionId(), "The runner image is not available");
        llm.enqueueToolCall("call_1", "sandbox_run", "{\"command\":\"true\"}");
        llm.enqueueText("The sandbox failed.");
        llm.enqueueText("Title");

        try (var device = localDevice(setup, new CopyOnWriteArrayList<>())) {
            send(setup, "Run it", null);
            device.awaitEvent("turn.completed", TIMEOUT);
        }

        var item = toolItem(setup, "sandbox_run");
        assertThat(item.path("toolStatus").asString()).isEqualTo("failed");
        assertThat(item.path("error").path("code").asString()).isEqualTo("executor_unavailable");
        assertThat(item.path("error").path("message").asString()).contains("The runner image is not available");
    }

    private GatewayTestClient localDevice(Setup setup, List<JsonNode> requests) throws Exception {
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
            var request = message.path("payload");
            requests.add(request);
            var response = new HashMap<String, Object>();
            response.put("callId", request.path("callId").asString());
            response.put("status", "succeeded");
            response.put("result", result(request.path("operation").asString()));
            device.send("exec.result", response);
        });
        device.subscribe(setup.sessionId(), 0);
        return device;
    }

    private static Map<String, Object> sandboxResult(String operation) {
        return switch (operation) {
            case "desktop_start", "pointer.desktop_status" ->
                Map.of(
                        "display",
                        ":99",
                        "running",
                        true,
                        "width",
                        1280,
                        "height",
                        800,
                        "imageWidth",
                        1280,
                        "imageHeight",
                        800,
                        "windowManager",
                        "openbox",
                        "alreadyRunning",
                        false);
            default -> result(operation);
        };
    }

    private void enableSandbox(Setup setup, boolean enabled) {
        client.patch()
                .uri("/api/v1/projects/{id}", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("sandboxEnabled", enabled))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.sandboxEnabled")
                .isEqualTo(enabled);
    }

    private void autoApprove(Setup setup) {
        client.patch()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("autoApprove", true))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private void enableVision(Setup setup) {
        var provider = client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(ProviderDtos.ProviderResponse[].class)
                .returnResult()
                .getResponseBody()[0];
        var model = provider.models().getFirst();
        client.patch()
                .uri("/api/v1/providers/{id}/models/{modelId}", provider.id(), model.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("supportsVision", true))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private JsonNode toolItem(Setup setup, String toolName) {
        var items = client.get()
                .uri("/api/v1/sessions/{id}/items", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        return StreamSupport.stream(items.spliterator(), false)
                .filter(item -> item.path("type").asString().equals("tool_call"))
                .filter(item -> item.path("toolName").asString().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + toolName + " item"));
    }

    private static List<String> operations(List<JsonNode> requests) {
        return requests.stream()
                .map(request -> request.path("operation").asString())
                .toList();
    }

    private static List<JsonNode> executed(List<JsonNode> requests, String operation) {
        return requests.stream()
                .filter(request -> request.path("operation").asString().equals(operation))
                .toList();
    }

    private static String description(JsonNode request, String name) {
        return StreamSupport.stream(request.path("tools").spliterator(), false)
                .filter(tool -> tool.path("function").path("name").asString().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool " + name + " in the request"))
                .path("function")
                .path("description")
                .asString();
    }

    private static List<String> toolNames(JsonNode request) {
        return StreamSupport.stream(request.path("tools").spliterator(), false)
                .map(tool -> tool.path("function").path("name").asString())
                .toList();
    }
}
