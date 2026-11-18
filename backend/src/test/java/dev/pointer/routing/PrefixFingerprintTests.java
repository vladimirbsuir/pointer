package dev.pointer.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.provider.llm.ChatRequest;
import dev.pointer.provider.llm.TokenEstimate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PrefixFingerprintTests {

    private static final String STEP = "step";
    private static final ChatRequest.ToolDefinition READ =
            new ChatRequest.ToolDefinition("read_file", "Read a file", Map.of("type", "object"));
    private static final ChatRequest.ToolDefinition GREP =
            new ChatRequest.ToolDefinition("grep", "Search files", Map.of("type", "object"));

    private final PrefixFingerprint fingerprint =
            new PrefixFingerprint(JsonMapper.builder().build());
    private final UUID sessionId = UUID.randomUUID();
    private final UUID modelId = UUID.randomUUID();

    @Test
    void theFirstCallHasNothingToReuse() {
        var reuse = fingerprint.measure(sessionId, STEP, modelId, request(List.of(READ), "system", "hello"));

        assertThat(reuse.prefixTokens()).isPositive();
        assertThat(reuse.prefixReuseTokens()).isNull();
    }

    @Test
    void anIdenticalRequestIsFullyReusable() {
        var request = request(List.of(READ), "system", "hello");
        fingerprint.measure(sessionId, STEP, modelId, request);

        var reuse = fingerprint.measure(sessionId, STEP, modelId, request);

        assertThat(reuse.prefixReuseTokens()).isEqualTo(reuse.prefixTokens());
    }

    @Test
    void appendedMessagesReuseEverythingBeforeThem() {
        var first = request(List.of(READ), "system", "hello");
        var previous = fingerprint.measure(sessionId, STEP, modelId, first);

        var reuse = fingerprint.measure(
                sessionId, STEP, modelId, request(List.of(READ), "system", "hello", "answer", "more"));

        assertThat(reuse.prefixReuseTokens()).isEqualTo(previous.prefixTokens());
        assertThat(reuse.prefixTokens()).isGreaterThan(previous.prefixTokens());
    }

    @Test
    void aChangedSystemPromptKeepsOnlyTheTools() {
        fingerprint.measure(sessionId, STEP, modelId, request(List.of(READ), "system", "hello"));

        var reuse = fingerprint.measure(sessionId, STEP, modelId, request(List.of(READ), "system v2", "hello"));

        var toolsOnly = reuse.prefixTokens()
                - TokenEstimate.message(ChatMessage.system("system v2"))
                - TokenEstimate.message(ChatMessage.user("hello"));
        assertThat(reuse.prefixReuseTokens()).isEqualTo(toolsOnly).isPositive();
    }

    @Test
    void aChangedToolListInvalidatesTheWholeRequest() {
        fingerprint.measure(sessionId, STEP, modelId, request(List.of(READ, GREP), "system", "hello"));

        var reordered = fingerprint.measure(sessionId, STEP, modelId, request(List.of(GREP, READ), "system", "hello"));

        assertThat(reordered.prefixReuseTokens()).isZero();
    }

    @Test
    void anotherModelCannotServeTheCache() {
        var request = request(List.of(READ), "system", "hello");
        fingerprint.measure(sessionId, STEP, modelId, request);

        assertThat(fingerprint
                        .measure(sessionId, STEP, UUID.randomUUID(), request)
                        .prefixReuseTokens())
                .isZero();
    }

    @Test
    void purposesAndSessionsAreTrackedSeparately() {
        var request = request(List.of(), "system", "hello");
        fingerprint.measure(sessionId, STEP, modelId, request);

        assertThat(fingerprint.measure(sessionId, "title", modelId, request).prefixReuseTokens())
                .isNull();
        assertThat(fingerprint
                        .measure(UUID.randomUUID(), STEP, modelId, request)
                        .prefixReuseTokens())
                .isNull();
    }

    @Test
    void forgetsTheLeastRecentlyUsedSessions() {
        var request = request(List.of(), "system", "hello");
        fingerprint.measure(sessionId, STEP, modelId, request);
        for (var index = 0; index < PrefixFingerprint.MAX_TRACKED; index++) {
            fingerprint.measure(UUID.randomUUID(), STEP, modelId, request);
        }

        assertThat(fingerprint.measure(sessionId, STEP, modelId, request).prefixReuseTokens())
                .isNull();
    }

    private static ChatRequest request(List<ChatRequest.ToolDefinition> tools, String system, String... turns) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(system));
        for (var index = 0; index < turns.length; index++) {
            messages.add(index % 2 == 0 ? ChatMessage.user(turns[index]) : ChatMessage.assistant(turns[index]));
        }
        return new ChatRequest("model", messages, tools, null);
    }
}
