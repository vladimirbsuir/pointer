package dev.pointer.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pointer.provider.EmbeddingPrefixes;
import dev.pointer.provider.ProviderService;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.provider.llm.ChatRequest;
import dev.pointer.provider.llm.Endpoint;
import dev.pointer.provider.llm.LlmException;
import dev.pointer.provider.llm.OpenAiCompatibleClient;
import dev.pointer.provider.llm.StreamListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

class RoutedClientTests {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID();
    private static final ChatRequest.ToolDefinition TOOL =
            new ChatRequest.ToolDefinition("read", "Reads a file", Map.of("type", "object"));

    private final OpenAiCompatibleClient llm = mock(OpenAiCompatibleClient.class);
    private final ProviderService providers = mock(ProviderService.class);
    private final RoutedClient client = new RoutedClient(llm, providers, Duration.ofMillis(10));
    private final List<String> switches = new ArrayList<>();
    private final ResolvedModel primary = model("primary", true);
    private final ModelRouter.RoutedModel routed = new ModelRouter.RoutedModel(primary, ModelRouter.MAIN);

    @Test
    void retriesTheSameModelOnceAndSucceeds() {
        answer(primary, unavailable(), text("ok"));

        var used = smart().stream(routed, request(List.of()), new StreamListener() {}, new Cancellation());

        assertThat(used).isSameAs(routed);
        verify(llm, times(2)).stream(eq(primary.endpoint()), any(), any(), any());
        verify(providers, never()).resolveFallbacks(any(), any(), any());
        assertThat(switches).isEmpty();
    }

    @Test
    void walksFallbacksInOrderAndKeepsTheWorkingOne() {
        var first = model("first", true);
        var second = model("second", true);
        when(providers.resolveFallbacks(USER, primary.modelId(), DEVICE)).thenReturn(List.of(first, second));
        answer(primary, unavailable(), unavailable());
        answer(first, new LlmException("provider_error", "slow down", 429));
        answer(second, text("ok"), text("again"));
        var failover = smart();

        var used = failover.stream(routed, request(List.of()), new StreamListener() {}, new Cancellation());
        var next = failover.stream(routed, request(List.of()), new StreamListener() {}, new Cancellation());

        assertThat(used.model()).isSameAs(second);
        assertThat(used.role()).isEqualTo(ModelRouter.FALLBACK);
        assertThat(next.model()).isSameAs(second);
        assertThat(switches).containsExactly("primary>first:down", "first>second:slow down");
        verify(llm, times(2)).stream(eq(primary.endpoint()), any(), any(), any());
        var sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm, times(2)).stream(eq(second.endpoint()), sent.capture(), any(), any());
        assertThat(sent.getValue().model()).isEqualTo("second");
    }

    @Test
    void returnsToThePrimaryWhenTheRememberedFallbackFails() {
        var backup = model("backup", true);
        when(providers.resolveFallbacks(USER, primary.modelId(), DEVICE)).thenReturn(List.of(backup));
        answer(primary, unavailable(), unavailable(), text("back"));
        answer(backup, text("ok"), unavailable(), unavailable());
        var failover = smart();

        failover.stream(routed, request(List.of()), new StreamListener() {}, new Cancellation());
        var used = failover.stream(routed, request(List.of()), new StreamListener() {}, new Cancellation());

        assertThat(used).isSameAs(routed);
        assertThat(switches).containsExactly("primary>backup:down", "backup>primary:down");
    }

    @Test
    void doesNotRetryAfterStreamingStarted() {
        when(providers.resolveFallbacks(any(), any(), any())).thenReturn(List.of(model("backup", true)));
        answer(primary, (Answer<Void>) invocation -> {
            invocation.<StreamListener>getArgument(2).onText("partial");
            throw unavailable();
        });

        assertThatThrownBy(
                        () -> smart().stream(routed, request(List.of()), new StreamListener() {}, new Cancellation()))
                .isInstanceOf(LlmException.class);

        verify(llm, times(1)).stream(any(), any(), any(), any());
        assertThat(switches).isEmpty();
    }

    @Test
    void doesNotRetryClientErrors() {
        answer(primary, new LlmException("provider_error", "bad request", 400));

        assertThatThrownBy(
                        () -> smart().stream(routed, request(List.of()), new StreamListener() {}, new Cancellation()))
                .hasMessage("bad request");

        verify(llm, times(1)).stream(any(), any(), any(), any());
    }

    @Test
    void skipsFallbacksWithoutToolSupportWhenTheRequestHasTools() {
        var plain = model("plain", false);
        var capable = model("capable", true);
        when(providers.resolveFallbacks(USER, primary.modelId(), DEVICE)).thenReturn(List.of(plain, capable));
        answer(primary, unavailable(), unavailable());
        answer(capable, text("ok"));

        var used = smart().stream(routed, request(List.of(TOOL)), new StreamListener() {}, new Cancellation());

        assertThat(used.model()).isSameAs(capable);
        verify(llm, never()).stream(eq(plain.endpoint()), any(), any(), any());
    }

    @Test
    void throwsTheLastFailureWhenEveryModelFails() {
        var backup = model("backup", true);
        when(providers.resolveFallbacks(USER, primary.modelId(), DEVICE)).thenReturn(List.of(backup));
        answer(primary, unavailable(), unavailable());
        answer(backup, new LlmException("provider_unreachable", "no route", new RuntimeException()));

        assertThatThrownBy(
                        () -> smart().stream(routed, request(List.of()), new StreamListener() {}, new Cancellation()))
                .hasMessage("no route");
    }

    @Test
    void stopsWaitingWhenTheTurnIsCancelled() {
        var cancellation = new Cancellation();
        answer(primary, (Answer<Void>) invocation -> {
            cancellation.cancel();
            throw new LlmException("provider_error", "down", 503, Duration.ofSeconds(15));
        });

        assertThatThrownBy(() -> smart().stream(routed, request(List.of()), new StreamListener() {}, cancellation))
                .hasMessage("down");

        verify(llm, times(1)).stream(any(), any(), any(), any());
    }

    @Test
    void routingOffCallsTheModelOnce() {
        answer(primary, unavailable());

        assertThatThrownBy(() -> client.failover(USER, DEVICE, false).stream(
                        routed, request(List.of()), new StreamListener() {}, new Cancellation()))
                .hasMessage("down");

        verify(llm, times(1)).stream(any(), any(), any(), any());
    }

    private RoutedClient.Failover smart() {
        return client.failover(
                USER,
                DEVICE,
                true,
                (from, to, failure) ->
                        switches.add(from.displayName() + ">" + to.displayName() + ":" + failure.getMessage()));
    }

    private void answer(ResolvedModel model, Object... outcomes) {
        var remaining = new ArrayList<>(List.of(outcomes));
        doAnswer(invocation -> {
                    var outcome = remaining.removeFirst();
                    if (outcome instanceof LlmException failure) {
                        throw failure;
                    }
                    return ((Answer<?>) outcome).answer(invocation);
                })
                .when(llm)
                .stream(eq(model.endpoint()), any(), any(), any());
    }

    private static Answer<Void> text(String text) {
        return invocation -> {
            invocation.<StreamListener>getArgument(2).onText(text);
            return null;
        };
    }

    private static LlmException unavailable() {
        return new LlmException("provider_error", "down", 503);
    }

    private static ChatRequest request(List<ChatRequest.ToolDefinition> tools) {
        return new ChatRequest("primary", List.of(ChatMessage.user("hi")), tools, null);
    }

    private static ResolvedModel model(String name, boolean supportsTools) {
        return new ResolvedModel(
                UUID.randomUUID(),
                name,
                name,
                8000,
                supportsTools,
                false,
                null,
                null,
                EmbeddingPrefixes.NONE,
                new Endpoint("http://" + name + ".test/v1", null, null));
    }
}
