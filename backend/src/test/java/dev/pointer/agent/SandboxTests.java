package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.eventKind;
import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.testing.GatewayTestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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

    @Test
    void sandboxSyncSendsTheLocalSnapshotThroughTheServerIntoTheSandbox() throws Exception {
        var setup = setUp(false);
        enableSandbox(setup, true);
        autoApprove(setup);
        llm.enqueueToolCall("call_1", "sandbox_sync", "{}");
        llm.enqueueText("Synced.");
        llm.enqueueText("Title");
        var archive = "gzip archive of the project".getBytes(StandardCharsets.UTF_8);
        var downloaded = new AtomicReference<byte[]>();
        var local = new CopyOnWriteArrayList<JsonNode>();
        var sandbox = new CopyOnWriteArrayList<JsonNode>();

        try (var device = localDevice(setup, local, request -> upload(request, archive))) {
            send(setup, "Sync the project", null);
            try (var runner = startRunnerAnswering(
                    setup, sandbox, List.of("executor", "desktop"), request -> download(request, downloaded))) {
                device.awaitEvent("turn.completed", TIMEOUT);
            }
        }

        assertThat(downloaded.get()).isEqualTo(archive);
        var snapshot = executed(local, "pointer.workspace_snapshot").getFirst();
        assertThat(snapshot.path("arguments").path("maxFiles").asInt()).isEqualTo(10_000);
        assertThat(snapshot.path("arguments").path("maxBytes").asLong()).isEqualTo(100L * 1024 * 1024);
        var extract = executed(sandbox, "pointer.sandbox_extract").getFirst();
        assertThat(extract.has("worktree")).isFalse();
        assertThat(operations(sandbox)).doesNotContain("pointer.workspace_snapshot");
        var item = toolItem(setup, "sandbox_sync");
        assertThat(item.path("toolStatus").asString()).isEqualTo("succeeded");
        assertThat(item.path("result").path("archiveBytes").asLong()).isEqualTo(archive.length);
        assertThat(item.path("resultText").asString())
                .contains("Synced 3 files (0.0 MB) from the user's computer to /workspace/project")
                .contains("Left out 1 secret-looking file: .env.")
                .contains("first sync of the session");
        assertThat(status(get(snapshot.path("arguments").path("uploadPath").asString())))
                .isEqualTo(404);
        assertThat(status(get(extract.path("arguments").path("downloadPath").asString())))
                .isEqualTo(404);
        assertThat(description(JSON.readTree(llm.requests().getFirst()), "sandbox_sync"))
                .contains("Edits never flow back");
    }

    @Test
    void sandboxSyncAsksWithTheDryRunCountsAndTheSandboxRecordsTheSync() throws Exception {
        var setup = setUp(false);
        enableSandbox(setup, true);
        llm.enqueueToolCall("call_1", "sandbox_sync", "{}");
        llm.enqueueToolCall("call_2", "sandbox_sync", "{}");
        llm.enqueueText("Synced twice.");
        llm.enqueueText("Title");
        var archive = "gzip archive of the project".getBytes(StandardCharsets.UTF_8);
        var local = new CopyOnWriteArrayList<JsonNode>();
        var sandbox = new CopyOnWriteArrayList<JsonNode>();

        assertThat(sandboxStatus(setup).path("offered").asBoolean()).isTrue();
        assertThat(sandboxStatus(setup).path("status").asString()).isEqualTo("none");
        try (var device = localDevice(setup, local, request -> upload(request, archive))) {
            send(setup, "Sync the project twice", null);
            var first = device.awaitEvent("approval.requested", TIMEOUT)
                    .path("payload")
                    .path("event");
            var preview = first.path("preview");
            assertThat(preview.path("kind").asString()).isEqualTo("sandbox_sync");
            assertThat(preview.path("target").asString()).isEqualTo("sandbox");
            assertThat(preview.path("files").asInt()).isEqualTo(3);
            assertThat(preview.path("bytes").asLong()).isEqualTo(2048);
            assertThat(preview.path("excluded").get(0).asString()).isEqualTo(".env");
            assertThat(preview.path("excludedCount").asInt()).isEqualTo(1);
            assertThat(preview.path("firstSync").asBoolean()).isTrue();
            assertThat(fakeRuntime().createdNamed("pointer-sandbox-" + setup.sessionId()))
                    .isEmpty();
            approve(setup, first.path("approvalId").asString());
            try (var runner = startRunnerAnswering(
                    setup,
                    sandbox,
                    List.of("executor", "desktop"),
                    request -> download(request, new AtomicReference<>()))) {
                var second = device.await(
                                message -> eventKind(message).equals("approval.requested")
                                        && !message.path("payload")
                                                .path("event")
                                                .path("approvalId")
                                                .asString()
                                                .equals(first.path("approvalId").asString()),
                                TIMEOUT)
                        .path("payload")
                        .path("event");
                assertThat(second.path("preview").path("firstSync").asBoolean()).isFalse();
                assertThat(second.path("preview").path("lastSyncAt").asString()).isNotBlank();
                approve(setup, second.path("approvalId").asString());
                device.awaitEvent("turn.completed", TIMEOUT);
            }
        }

        var dryRuns = executed(local, "pointer.workspace_snapshot").stream()
                .filter(request -> request.path("dryRun").asBoolean())
                .toList();
        assertThat(dryRuns).hasSize(2);
        assertThat(dryRuns.getFirst().path("arguments").has("uploadPath")).isFalse();
        var status = sandboxStatus(setup);
        assertThat(status.path("offered").asBoolean()).isTrue();
        assertThat(status.path("status").asString()).isEqualTo("ready");
        assertThat(status.path("workspace").asString()).isEqualTo("/workspace/project");
        assertThat(status.path("syncedFiles").asInt()).isEqualTo(3);
        assertThat(status.path("syncedBytes").asLong()).isEqualTo(2048);
        assertThat(status.path("syncedAt").asString()).isNotBlank();
    }

    @Test
    void aSnapshotThatIsTooLargeFailsBeforeAsking() throws Exception {
        var setup = setUp(false);
        enableSandbox(setup, true);
        llm.enqueueToolCall("call_1", "sandbox_sync", "{}");
        llm.enqueueText("Too large.");
        llm.enqueueText("Title");

        try (var device = GatewayTestClient.connect(
                port,
                setup.auth().accessToken(),
                setup.deviceId(),
                List.of("viewer", "executor"),
                List.of(setup.projectId().toString()))) {
            device.onMessage(message -> {
                if (!type(message).equals("exec.request")) {
                    return;
                }
                device.send(
                        "exec.result",
                        Map.of(
                                "callId",
                                message.path("payload").path("callId").asString(),
                                "status",
                                "failed",
                                "error",
                                Map.of("code", "snapshot_too_large", "message", "The project has 20000 files")));
            });
            device.subscribe(setup.sessionId(), 0);
            send(setup, "Sync the project", null);
            device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(device.events().stream().map(GatewayTestClient::eventKind))
                    .doesNotContain("approval.requested");
        }

        var item = toolItem(setup, "sandbox_sync");
        assertThat(item.path("toolStatus").asString()).isEqualTo("failed");
        assertThat(item.path("error").path("code").asString()).isEqualTo("snapshot_too_large");
        assertThat(sandboxStatus(setup).path("status").asString()).isEqualTo("none");
    }

    @Test
    void aProjectWithoutTheSandboxIsNotOfferedOne() throws Exception {
        var setup = setUp(false);
        enableSandbox(setup, false);

        assertThat(sandboxStatus(setup).path("offered").asBoolean()).isFalse();
    }

    @Test
    void aFailedSnapshotDoesNotStartTheSandbox() throws Exception {
        var setup = setUp(false);
        enableSandbox(setup, true);
        autoApprove(setup);
        llm.enqueueToolCall("call_1", "sandbox_sync", "{}");
        llm.enqueueText("Too large.");
        llm.enqueueText("Title");

        try (var device = localDevice(setup, new CopyOnWriteArrayList<>(), request -> Map.of())) {
            send(setup, "Sync the project", null);
            device.awaitEvent("turn.completed", TIMEOUT);
        }

        var item = toolItem(setup, "sandbox_sync");
        assertThat(item.path("toolStatus").asString()).isEqualTo("failed");
        assertThat(item.path("error").path("code").asString()).isEqualTo("upload_failed");
        assertThat(fakeRuntime().createdNamed("pointer-sandbox-" + setup.sessionId()))
                .isEmpty();
    }

    private Map<String, Object> upload(JsonNode request, byte[] archive) {
        if (!request.path("operation").asString().equals("pointer.workspace_snapshot")) {
            return result(request.path("operation").asString());
        }
        if (request.path("dryRun").asBoolean()) {
            return Map.of("files", 3, "bytes", 2048, "excluded", List.of(".env"), "excludedCount", 1);
        }
        var response = http(HttpRequest.newBuilder(
                        uri(request.path("arguments").path("uploadPath").asString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(archive))
                .build());
        assertThat(response.statusCode()).isEqualTo(204);
        return Map.of("files", 3, "bytes", 2048, "excluded", List.of(".env"), "excludedCount", 1);
    }

    private Map<String, Object> download(JsonNode request, AtomicReference<byte[]> downloaded) {
        if (!request.path("operation").asString().equals("pointer.sandbox_extract")) {
            return sandboxResult(request.path("operation").asString());
        }
        var response = get(request.path("arguments").path("downloadPath").asString());
        assertThat(response.statusCode()).isEqualTo(200);
        downloaded.set(response.body());
        return Map.of("files", 3, "bytes", 2048, "deleted", 0, "firstSync", true, "workspace", "/workspace/project");
    }

    private HttpResponse<byte[]> get(String path) {
        return http(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private static int status(HttpResponse<byte[]> response) {
        return response.statusCode();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<byte[]> http(HttpRequest request) {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private GatewayTestClient localDevice(Setup setup, List<JsonNode> requests) throws Exception {
        return localDevice(
                setup, requests, request -> result(request.path("operation").asString()));
    }

    private GatewayTestClient localDevice(
            Setup setup, List<JsonNode> requests, Function<JsonNode, Map<String, Object>> results) throws Exception {
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
            response.put("result", results.apply(request));
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

    private JsonNode sandboxStatus(Setup setup) {
        return client.get()
                .uri("/api/v1/sessions/{id}/sandbox", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private void approve(Setup setup, String approvalId) {
        client.post()
                .uri("/api/v1/sessions/{id}/approvals/{approvalId}", setup.sessionId(), approvalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("decision", "approved", "scope", "once"))
                .exchange()
                .expectStatus()
                .isNoContent();
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
