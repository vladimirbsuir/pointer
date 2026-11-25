package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.ProviderDtos;
import dev.pointer.testing.GatewayTestClient;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DesktopToolTests extends AgentIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    @Test
    void anAgentStartsTheDisplayClicksAndSeesScreenshotsAfterTheToolResults() throws Exception {
        var setup = setUp(false);
        configureModel(setup, "fake-model", Map.of("supportsVision", true));
        session(setup, Map.of("autoApprove", true));
        llm.enqueueToolCall("call_1", "desktop_start", "{}");
        llm.enqueueToolCall("call_2", "desktop_click", "{\"x\":640,\"y\":360}");
        llm.enqueueToolCall("call_3", "desktop_screenshot", "{}");
        llm.enqueueText("The button works.");
        llm.enqueueText("Title");
        var largest = image(MAX_IMAGE_BYTES);
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var runner = runner(setup, requests, new Display(1280, 800, 1280, 800), request -> largest)) {
            send(setup, "Check the button", null);
            runner.awaitEvent("turn.completed", TIMEOUT);
        }

        var firstRequest = JSON.readTree(llm.requests().getFirst());
        var click = tool(firstRequest, "desktop_click");
        assertThat(click.path("function").path("description").asString())
                .contains("pixels of the latest full screenshot");
        assertThat(tool(firstRequest, "desktop_type")
                        .path("function")
                        .path("description")
                        .asString())
                .doesNotContain("Screenshot coordinates");

        var start = executed(requests, "desktop_start").getFirst().path("arguments");
        assertThat(start.path("width").asInt()).isEqualTo(1280);
        assertThat(start.path("maxImageWidth").asInt()).isEqualTo(1280);
        assertThat(start.path("maxImageHeight").asInt()).isEqualTo(800);
        assertThat(start.path("maxImageBytes").asLong()).isEqualTo(MAX_IMAGE_BYTES);
        var clicked = executed(requests, "desktop_click").getFirst().path("arguments");
        assertThat(clicked.path("x").asInt()).isEqualTo(640);
        assertThat(clicked.path("y").asInt()).isEqualTo(360);
        assertThat(clicked.path("settleMs").asInt()).isEqualTo(300);

        var clickItem = toolItem(setup, "desktop_click");
        assertThat(clickItem.path("toolStatus").asString()).isEqualTo("succeeded");
        assertThat(clickItem.path("result").has("images")).isFalse();
        assertThat(clickItem.path("result").path("x").asInt()).isEqualTo(640);
        assertThat(clickItem.path("resultText").asString()).endsWith("\nScreenshot attached.");
        assertThat(clickItem.path("marks").get(0).path("x").asInt()).isEqualTo(640);
        assertThat(clickItem.path("marks").get(0).path("y").asInt()).isEqualTo(360);
        var image = clickItem.path("images").get(0);
        assertThat(image.path("mediaType").asString()).isEqualTo("image/png");
        assertThat(image.path("width").asInt()).isEqualTo(1280);
        assertThat(image.path("zoom").asBoolean()).isFalse();
        var downloaded = attachment(setup, image.path("attachmentId").asString());
        assertThat(downloaded).isEqualTo(largest);

        var startItem = toolItem(setup, "desktop_start");
        assertThat(startItem.path("result").path("coordinates").asString()).isEqualTo("pixels");
        assertThat(startItem.path("result").path("width").asInt()).isEqualTo(1280);
        assertThat(startItem.has("images")).isFalse();

        var afterClick = messages(llm.requests().get(2));
        var screenshots = afterClick.getLast();
        assertThat(screenshots.path("role").asString()).isEqualTo("user");
        assertThat(screenshots.path("content").get(0).path("text").asString())
                .isEqualTo("Screenshots from this step, in call order:\n1. desktop_click({\"x\":640,\"y\":360})");
        assertThat(screenshots
                        .path("content")
                        .get(1)
                        .path("image_url")
                        .path("url")
                        .asString())
                .isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(largest));
        var toolResult = afterClick.get(afterClick.size() - 2);
        assertThat(toolResult.path("role").asString()).isEqualTo("tool");
        assertThat(toolResult.path("tool_call_id").asString()).isEqualTo("call_2");
    }

    @Test
    void aGridModelOnALargeDisplayIsMappedBothWaysAndOutOfRangeCallsNeverRun() throws Exception {
        var setup = setUp(false);
        configureModel(setup, "fake-model", Map.of("supportsVision", true, "coordinateSystem", "normalized"));
        session(setup, Map.of("autoApprove", true));
        llm.enqueueToolCall("call_1", "desktop_start", "{\"width\":1920,\"height\":1200}");
        llm.enqueueToolCall("call_2", "desktop_click", "{\"x\":500,\"y\":1000}");
        llm.enqueueToolCall("call_3", "desktop_click", "{\"x\":1001,\"y\":5}");
        llm.enqueueText("Done.");
        llm.enqueueText("Title");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var runner = runner(setup, requests, new Display(1920, 1200, 1280, 800), request -> image(64))) {
            send(setup, "Click the corner", null);
            runner.awaitEvent("turn.completed", TIMEOUT);
        }

        var firstRequest = JSON.readTree(llm.requests().getFirst());
        assertThat(tool(firstRequest, "desktop_click")
                        .path("function")
                        .path("description")
                        .asString())
                .contains("0 to 1000 grid");
        var clicks = executed(requests, "desktop_click");
        assertThat(clicks).hasSize(1);
        assertThat(clicks.getFirst().path("arguments").path("x").asInt()).isEqualTo(960);
        assertThat(clicks.getFirst().path("arguments").path("y").asInt()).isEqualTo(1199);

        var items = toolItems(setup, "desktop_click");
        assertThat(items.getFirst().path("result").path("x").asInt()).isEqualTo(500);
        assertThat(items.getFirst().path("result").path("y").asInt()).isEqualTo(1000);
        assertThat(items.getFirst().path("marks").get(0).path("x").asInt()).isEqualTo(640);
        assertThat(items.getFirst().path("marks").get(0).path("y").asInt()).isEqualTo(799);
        assertThat(items.getLast().path("toolStatus").asString()).isEqualTo("failed");
        assertThat(items.getLast().path("error").path("code").asString()).isEqualTo("invalid_coordinates");
        var start = toolItem(setup, "desktop_start").path("result");
        assertThat(start.path("coordinates").asString()).isEqualTo("normalized");
        assertThat(start.path("width").asInt()).isEqualTo(1280);
        assertThat(start.path("height").asInt()).isEqualTo(800);
    }

    @Test
    void desktopToolsNeedAVisionModelAndADesktopDevice() throws Exception {
        var blind = setUp(false);
        llm.enqueueText("Answer.");
        llm.enqueueText("Title");
        try (var runner = runner(blind, new CopyOnWriteArrayList<>(), Display.DEFAULT, request -> image(64))) {
            send(blind, "Hello", null);
            runner.awaitEvent("turn.completed", TIMEOUT);
        }
        var blindTools = toolNames(JSON.readTree(llm.requests().getFirst()));
        assertThat(blindTools).contains("read_file").noneMatch(name -> name.startsWith("desktop_"));

        var plain = setUp(false);
        configureModel(plain, "fake-model", Map.of("supportsVision", true));
        llm.enqueueText("Answer.");
        llm.enqueueText("Title");
        try (var device = GatewayTestClient.connect(
                port,
                plain.auth().accessToken(),
                plain.deviceId(),
                List.of("viewer", "executor"),
                List.of(plain.projectId().toString()))) {
            device.onMessage(message -> answer(device, message, request -> Map.of("status", "succeeded")));
            device.subscribe(plain.sessionId(), 0);
            send(plain, "Hello", null);
            device.awaitEvent("turn.completed", TIMEOUT);
        }
        var plainTools = toolNames(JSON.readTree(llm.requests().get(2)));
        assertThat(plainTools).contains("read_file").noneMatch(name -> name.startsWith("desktop_"));
    }

    @Test
    void aBlindFastTierEscalatesToTheSessionModelInsteadOfRunningADesktopTool() throws Exception {
        var setup = setUp(false);
        configureModel(setup, "fake-model", Map.of("supportsVision", true, "contextWindow", 100_000));
        var fast = addModel(setup, "fast-model");
        settings(setup, Map.of("fastModelId", fast.toString()));
        session(setup, Map.of("routing", "smart", "escalation", "on", "autoApprove", true));
        llm.enqueueToolCall("call_1", "desktop_start", "{}");
        llm.enqueueText("Started on the session model.");
        llm.enqueueText("Title");
        var requests = new CopyOnWriteArrayList<JsonNode>();

        try (var runner = runner(setup, requests, Display.DEFAULT, request -> image(64))) {
            send(setup, "Start the display", null);
            runner.awaitEvent("turn.completed", TIMEOUT);
        }

        var models = llm.requests().stream()
                .limit(2)
                .map(request -> JSON.readTree(request).path("model").asString())
                .toList();
        assertThat(models).containsExactly("fast-model", "fake-model");
        assertThat(toolNames(JSON.readTree(llm.requests().getFirst()))).contains("desktop_start");
        assertThat(executed(requests, "desktop_start")).isEmpty();
        var call = toolItem(setup, "desktop_start");
        assertThat(call.path("toolStatus").asString()).isEqualTo("failed");
        assertThat(call.path("error").path("code").asString()).isEqualTo("needs_vision");
        assertThat(call.path("resultText").asString()).contains("The turn continues on fake-model.");
        var notice = item(setup, "model_escalated");
        assertThat(notice.path("reason").asString()).isEqualTo("vision");
        assertThat(notice.path("from").asString()).isEqualTo("fast-model");
        assertThat(notice.path("to").asString()).isEqualTo("fake-model");
    }

    @Test
    void oversizedAndMalformedScreenshotsFailTheCallWithoutStoringAnything() throws Exception {
        var setup = setUp(false);
        configureModel(setup, "fake-model", Map.of("supportsVision", true));
        llm.enqueueToolCall("call_1", "desktop_screenshot", "{}");
        llm.enqueueToolCall("call_2", "desktop_screenshot", "{}");
        llm.enqueueText("Done.");
        llm.enqueueText("Title");
        var shots = new ArrayList<byte[]>(List.of(image(MAX_IMAGE_BYTES + 1), "not a png".getBytes()));

        try (var runner =
                runner(setup, new CopyOnWriteArrayList<>(), Display.DEFAULT, request -> shots.removeFirst())) {
            send(setup, "Look", null);
            runner.awaitEvent("turn.completed", TIMEOUT);
        }

        var items = toolItems(setup, "desktop_screenshot");
        assertThat(items)
                .extracting(item -> item.path("error").path("code").asString())
                .containsExactly("image_too_large", "invalid_image");
        assertThat(items).allSatisfy(item -> assertThat(item.has("images")).isFalse());
    }

    private GatewayTestClient runner(
            Setup setup,
            List<JsonNode> requests,
            Display display,
            java.util.function.Function<JsonNode, byte[]> screenshots)
            throws Exception {
        var device = GatewayTestClient.connect(
                port,
                setup.auth().accessToken(),
                setup.deviceId(),
                List.of("viewer", "executor", "desktop"),
                List.of(setup.projectId().toString()));
        device.onMessage(message -> answer(device, message, request -> {
            requests.add(request);
            return respond(request, display, screenshots);
        }));
        device.subscribe(setup.sessionId(), 0);
        return device;
    }

    private static void answer(
            GatewayTestClient device,
            JsonNode message,
            java.util.function.Function<JsonNode, Map<String, Object>> responder) {
        if (!type(message).equals("exec.request")) {
            return;
        }
        var request = message.path("payload");
        var response = new HashMap<String, Object>(responder.apply(request));
        response.put("callId", request.path("callId").asString());
        device.send("exec.result", response);
    }

    private static Map<String, Object> respond(
            JsonNode request, Display display, java.util.function.Function<JsonNode, byte[]> screenshots) {
        var arguments = request.path("arguments");
        var result = new LinkedHashMap<String, Object>();
        switch (request.path("operation").asString()) {
            case "desktop_start", "pointer.desktop_status" -> {
                result.put("display", ":99");
                result.put("running", true);
                result.put("width", display.screenWidth());
                result.put("height", display.screenHeight());
                result.put("imageWidth", display.imageWidth());
                result.put("imageHeight", display.imageHeight());
                result.put("windowManager", "openbox");
                result.put("alreadyRunning", false);
            }
            case "desktop_click" -> {
                result.put("x", arguments.path("x").asInt());
                result.put("y", arguments.path("y").asInt());
                result.put("images", List.of(screenshot(display, screenshots.apply(request))));
            }
            case "desktop_screenshot" -> {
                result.put("width", display.imageWidth());
                result.put("height", display.imageHeight());
                result.put("screenWidth", display.screenWidth());
                result.put("screenHeight", display.screenHeight());
                result.put("images", List.of(screenshot(display, screenshots.apply(request))));
            }
            default -> {}
        }
        return Map.of("status", "succeeded", "result", result);
    }

    private static Map<String, Object> screenshot(Display display, byte[] data) {
        return Map.of(
                "mediaType", "image/png",
                "width", display.imageWidth(),
                "height", display.imageHeight(),
                "screenWidth", display.screenWidth(),
                "screenHeight", display.screenHeight(),
                "data", Base64.getEncoder().encodeToString(data));
    }

    private static byte[] image(int size) {
        var data = new byte[size];
        ThreadLocalRandom.current().nextBytes(data);
        System.arraycopy(PNG, 0, data, 0, PNG.length);
        return data;
    }

    private static List<JsonNode> executed(List<JsonNode> requests, String operation) {
        return requests.stream()
                .filter(request -> request.path("operation").asString().equals(operation))
                .toList();
    }

    private static JsonNode tool(JsonNode request, String name) {
        return StreamSupport.stream(request.path("tools").spliterator(), false)
                .filter(tool -> tool.path("function").path("name").asString().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool " + name + " in the request"));
    }

    private static List<String> toolNames(JsonNode request) {
        return StreamSupport.stream(request.path("tools").spliterator(), false)
                .map(tool -> tool.path("function").path("name").asString())
                .toList();
    }

    private static List<JsonNode> messages(String request) {
        return StreamSupport.stream(JSON.readTree(request).path("messages").spliterator(), false)
                .toList();
    }

    private JsonNode toolItem(Setup setup, String toolName) {
        return toolItems(setup, toolName).getFirst();
    }

    private List<JsonNode> toolItems(Setup setup, String toolName) {
        return StreamSupport.stream(items(setup).spliterator(), false)
                .filter(item -> item.path("type").asString().equals("tool_call"))
                .filter(item -> item.path("toolName").asString().equals(toolName))
                .toList();
    }

    private JsonNode item(Setup setup, String type) {
        return StreamSupport.stream(items(setup).spliterator(), false)
                .filter(item -> item.path("type").asString().equals(type))
                .findFirst()
                .orElseThrow();
    }

    private JsonNode items(Setup setup) {
        return client.get()
                .uri("/api/v1/sessions/{id}/items", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private byte[] attachment(Setup setup, String attachmentId) {
        return client.get()
                .uri("/api/v1/sessions/{id}/attachments/{attachmentId}", setup.sessionId(), attachmentId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
    }

    private ProviderDtos.ProviderResponse provider(Setup setup) {
        return client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(ProviderDtos.ProviderResponse[].class)
                .returnResult()
                .getResponseBody()[0];
    }

    private void configureModel(Setup setup, String modelKey, Map<String, Object> changes) {
        var provider = provider(setup);
        var model = provider.models().stream()
                .filter(candidate -> candidate.modelKey().equals(modelKey))
                .findFirst()
                .orElseThrow();
        client.patch()
                .uri("/api/v1/providers/{id}/models/{modelId}", provider.id(), model.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(changes)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private UUID addModel(Setup setup, String modelKey) {
        return client.post()
                .uri("/api/v1/providers/{id}/models", provider(setup).id())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelKey", modelKey, "contextWindow", 8000))
                .exchange()
                .expectBody(ProviderDtos.ModelResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
    }

    private void settings(Setup setup, Map<String, Object> body) {
        client.put()
                .uri("/api/v1/me/settings")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private void session(Setup setup, Map<String, Object> body) {
        client.patch()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private record Display(int screenWidth, int screenHeight, int imageWidth, int imageHeight) {

        static final Display DEFAULT = new Display(1280, 800, 1280, 800);
    }
}
