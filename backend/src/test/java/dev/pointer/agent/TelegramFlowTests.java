package dev.pointer.agent;

import static dev.pointer.testing.FakeTelegramServer.button;
import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.testing.FakeTelegramServer;
import dev.pointer.testing.GatewayTestClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

class TelegramFlowTests extends AgentIntegrationTest {

    private static final FakeTelegramServer TELEGRAM = new FakeTelegramServer();

    @DynamicPropertySource
    static void telegram(DynamicPropertyRegistry registry) {
        registry.add("pointer.telegram.bot-token", () -> FakeTelegramServer.TOKEN);
        registry.add("pointer.telegram.api-url", TELEGRAM::apiUrl);
        registry.add("pointer.telegram.poll-timeout", () -> "1s");
    }

    @Test
    void unknownChatAndInvalidCodeAreRejected() {
        var chatId = newChatId();
        TELEGRAM.enqueueText(chatId, "hello");
        TELEGRAM.awaitMessage(chatId, "not linked", TIMEOUT);

        TELEGRAM.enqueueText(chatId, "/start not-a-real-code");
        TELEGRAM.awaitMessage(chatId, "invalid or expired", TIMEOUT);
    }

    @Test
    void linkedChatDrivesSessionWithInlineApproval() throws Exception {
        var setup = setUp(false);
        var chatId = link(setup);
        assertThat(status(setup).path("linked").asBoolean()).isTrue();
        llm.enqueueToolCall("call_1", "edit_file", "{\"path\":\"a.txt\",\"oldText\":\"one\",\"newText\":\"two\"}");
        llm.enqueueText("Changed **one** to `two`.");

        try (var device = executor(
                setup,
                request -> request.path("dryRun").asBoolean()
                        ? succeeded(Map.of("preview", Map.of("path", "a.txt", "unifiedDiff", "-one\n+two\n")))
                        : succeeded(Map.of("replacements", 1)))) {
            TELEGRAM.enqueueText(chatId, "/sessions");
            var list = TELEGRAM.awaitMessage(chatId, "Recent sessions", TIMEOUT);
            TELEGRAM.enqueueCallback(chatId, list.messageId(), button(list, "New session"));
            TELEGRAM.awaitMessage(chatId, "Selected", TIMEOUT);

            TELEGRAM.enqueueText(chatId, "Change one to two");
            TELEGRAM.awaitMessage(chatId, "Working", TIMEOUT);
            var approval = TELEGRAM.awaitMessage(chatId, "Approval needed", TIMEOUT);
            assertThat(approval.body().path("text").asString())
                    .contains("a.txt")
                    .contains("+two");

            TELEGRAM.enqueueCallback(chatId, approval.messageId(), button(approval, "Approve"));
            device.awaitEvent("turn.completed", TIMEOUT);

            var answer = TELEGRAM.awaitMessage(chatId, "Changed", TIMEOUT);
            assertThat(answer.body().path("text").asString()).isEqualTo("Changed <b>one</b> to <code>two</code>.");
            TELEGRAM.awaitEdit(chatId, "Approved", TIMEOUT);
            TELEGRAM.awaitEdit(chatId, "Done", TIMEOUT);
        }
        assertThat(llm.requests().getFirst()).contains("Change one to two");
    }

    @Test
    void replyToApprovalDeniesWithMessage() throws Exception {
        var setup = setUp(false);
        var chatId = link(setup);
        select(chatId);
        llm.enqueueToolCall("call_1", "run_command", "{\"command\":\"rm -rf build\"}");
        llm.enqueueText("Okay, I will not delete it.");

        try (var device = executor(setup, request -> succeeded(Map.of()))) {
            TELEGRAM.enqueueText(chatId, "Clean the build");
            var approval = TELEGRAM.awaitMessage(chatId, "rm -rf build", TIMEOUT);

            TELEGRAM.enqueueReply(chatId, "keep the build folder", approval.messageId());
            var completed = device.awaitEvent("turn.completed", TIMEOUT);
            assertThat(completed.path("payload").path("event").path("reason").asString())
                    .isEqualTo("completed");
            TELEGRAM.awaitEdit(chatId, "Denied: keep the build folder", TIMEOUT);
        }
        assertThat(llm.requests().get(1)).contains("keep the build folder");
    }

    @Test
    void answersFromOtherSessionsArriveWithOpenButton() throws Exception {
        var setup = setUp(false);
        var chatId = link(setup);
        llm.enqueueText("Answer from the desktop.");

        try (var device = executor(setup, request -> succeeded(Map.of()))) {
            send(setup, "Question from the desktop", null).expectStatus().isAccepted();
            device.awaitEvent("turn.completed", TIMEOUT);
        }

        var answer = TELEGRAM.awaitMessage(chatId, "Answer from the desktop.", TIMEOUT);
        assertThat(answer.body().path("text").asString()).startsWith("<b>");
        assertThat(button(answer, "Open session")).startsWith("s");
        assertThat(TELEGRAM.calls().stream()
                        .filter(call -> call.body().path("chat_id").asLong() == chatId)
                        .map(call -> call.body().path("text").asString()))
                .noneMatch(text -> text.contains("Working"));
    }

    @Test
    void newSessionStartsOnTheOnlineDevice() throws Exception {
        var setup = setUp(false);
        var chatId = link(setup);

        try (var device = executor(setup, request -> succeeded(Map.of()))) {
            TELEGRAM.enqueueText(chatId, "/new");
            var projects = TELEGRAM.awaitMessage(chatId, "Choose a project", TIMEOUT);
            TELEGRAM.enqueueCallback(chatId, projects.messageId(), button(projects, "demo"));
            TELEGRAM.awaitMessage(chatId, "New session in", TIMEOUT);
        }

        var sessions = client.get()
                .uri("/api/v1/projects/{id}/sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).path("executorDeviceId").asString())
                .isEqualTo(setup.deviceId().toString());
    }

    @Test
    void unlinkFromDesktopNotifiesChat() {
        var setup = setUp(false);
        var chatId = link(setup);

        client.delete()
                .uri("/api/v1/telegram/link")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isNoContent();

        TELEGRAM.awaitMessage(chatId, "unlinked", TIMEOUT);
        assertThat(status(setup).path("linked").asBoolean()).isFalse();
        TELEGRAM.enqueueText(chatId, "/sessions");
        TELEGRAM.awaitMessage(chatId, "not linked", TIMEOUT);
    }

    private long link(Setup setup) {
        var chatId = newChatId();
        var url = linkUrl(setup);
        assertThat(url).startsWith("https://t.me/" + FakeTelegramServer.BOT_USERNAME + "?start=");
        TELEGRAM.enqueueText(chatId, "/start " + url.substring(url.indexOf("start=") + "start=".length()));
        TELEGRAM.awaitMessage(chatId, "Linked", TIMEOUT);
        return chatId;
    }

    private void select(long chatId) {
        TELEGRAM.enqueueText(chatId, "/sessions");
        var list = TELEGRAM.awaitMessage(chatId, "Recent sessions", TIMEOUT);
        TELEGRAM.enqueueCallback(chatId, list.messageId(), button(list, "New session"));
        TELEGRAM.awaitMessage(chatId, "Selected", TIMEOUT);
    }

    private String linkUrl(Setup setup) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            var response = client.post()
                    .uri("/api/v1/telegram/link")
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .returnResult(JsonNode.class);
            if (response.getStatus().is2xxSuccessful()) {
                return response.getResponseBody().path("url").asString();
            }
            assertThat(Instant.now()).isBefore(deadline);
            sleep(Duration.ofMillis(100));
        }
    }

    private JsonNode status(Setup setup) {
        return client.get()
                .uri("/api/v1/telegram")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private GatewayTestClient executor(Setup setup, Function<JsonNode, Map<String, Object>> responder)
            throws Exception {
        var device = GatewayTestClient.connect(
                port,
                setup.auth().accessToken(),
                setup.deviceId(),
                List.of("viewer", "executor"),
                List.of(setup.projectId().toString()));
        device.onMessage(message -> {
            if (type(message).equals("exec.request")) {
                var request = message.path("payload");
                var response = new HashMap<String, Object>(responder.apply(request));
                response.put("callId", request.path("callId").asString());
                device.send("exec.result", response);
            }
        });
        device.subscribe(setup.sessionId(), 0);
        return device;
    }

    private static Map<String, Object> succeeded(Map<String, Object> result) {
        return Map.of("status", "succeeded", "result", result);
    }

    private static long newChatId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
