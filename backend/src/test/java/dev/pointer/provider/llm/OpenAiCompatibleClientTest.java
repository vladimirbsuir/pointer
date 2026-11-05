package dev.pointer.provider.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pointer.testing.FakeOpenAiServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleClientTest {

    private final FakeOpenAiServer server = new FakeOpenAiServer();
    private final OpenAiCompatibleClient client =
            new OpenAiCompatibleClient(JsonMapper.builder().build());

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void listsModels() {
        server.withModels("gpt-a", "gpt-b");

        assertThat(client.listModels(endpoint(null))).containsExactly("gpt-a", "gpt-b");
    }

    @Test
    void streamsTextAndUsage() {
        server.enqueueText("Hel", "lo");
        var listener = new RecordingListener();

        client.stream(endpoint(null), request(), listener, new Cancellation());

        assertThat(String.join("", listener.text)).isEqualTo("Hello");
        assertThat(listener.usage).isEqualTo(new StreamListener.Usage(12, 5));
        assertThat(server.requests().getFirst()).contains("\"stream\":true").contains("\"role\":\"user\"");
    }

    @Test
    void streamsReasoningAndToolCallDeltas() {
        server.enqueueStream(
                Duration.ZERO,
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":1}\"}}]}}]}",
                "[DONE]");
        var listener = new RecordingListener();

        client.stream(endpoint(null), request(), listener, new Cancellation());

        assertThat(listener.reasoning).containsExactly("thinking");
        assertThat(listener.toolCalls).containsExactly("0:call_1:read_file:{\"pa", "0:null:null:th\":1}");
    }

    @Test
    void reasoningSentInBothFieldsIsReportedOnce() {
        server.enqueueStream(
                Duration.ZERO,
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"The user\",\"reasoning\":\"The user\"}}]}",
                "{\"choices\":[{\"delta\":{\"reasoning\":\" wants\"}}]}",
                "[DONE]");
        var listener = new RecordingListener();

        client.stream(endpoint(null), request(), listener, new Cancellation());

        assertThat(listener.reasoning).containsExactly("The user", " wants");
    }

    @Test
    void sendsApiKeyAndReportsUnauthorized() {
        server.requireApiKey("secret");

        assertThat(client.listModels(endpoint("secret"))).isNotEmpty();
        assertThatThrownBy(() -> client.listModels(endpoint("wrong")))
                .isInstanceOfSatisfying(LlmException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("provider_unauthorized");
                    assertThat(exception.getMessage()).isEqualTo("Invalid API key");
                });
    }

    @Test
    void reportsProviderErrors() {
        server.enqueueError(400, "{\"error\":{\"message\":\"model not found\"}}");

        assertThatThrownBy(() -> client.stream(endpoint(null), request(), new RecordingListener(), new Cancellation()))
                .isInstanceOfSatisfying(LlmException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("provider_error");
                    assertThat(exception.status()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo("model not found");
                });
    }

    @Test
    void cancellationStopsStreamingPromptly() throws Exception {
        var lines = new ArrayList<String>();
        for (var i = 0; i < 100; i++) {
            lines.add("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}");
        }
        server.enqueueStream(Duration.ofMillis(50), lines.toArray(String[]::new));
        var listener = new RecordingListener();
        var cancellation = new Cancellation();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> client.stream(endpoint(null), request(), listener, cancellation));
            Thread.sleep(200);
            cancellation.cancel();
            future.get(2, TimeUnit.SECONDS);
        }

        assertThat(listener.text.size()).isLessThan(100);
    }

    @Test
    void cancellationAbortsRequestWaitingForResponseHeaders() throws Exception {
        server.enqueueSilence(Duration.ofSeconds(30));
        var cancellation = new Cancellation();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var started = System.nanoTime();
            var future = executor.submit(
                    () -> client.stream(endpoint(null), request(), new RecordingListener(), cancellation));
            Thread.sleep(300);
            cancellation.cancel();
            future.get(3, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        }
    }

    private Endpoint endpoint(String apiKey) {
        return new Endpoint(server.baseUrl(), apiKey, new DirectHttpTransport());
    }

    private static ChatRequest request() {
        return ChatRequest.of("fake-model", List.of(ChatMessage.user("Hi")));
    }

    private static final class RecordingListener implements StreamListener {
        final List<String> text = new ArrayList<>();
        final List<String> reasoning = new ArrayList<>();
        final List<String> toolCalls = new ArrayList<>();
        Usage usage;

        @Override
        public void onText(String delta) {
            text.add(delta);
        }

        @Override
        public void onReasoning(String delta) {
            reasoning.add(delta);
        }

        @Override
        public void onToolCallDelta(int index, String id, String name, String argumentsDelta) {
            toolCalls.add(index + ":" + id + ":" + name + ":" + argumentsDelta);
        }

        @Override
        public void onUsage(Usage usage) {
            this.usage = usage;
        }
    }
}
