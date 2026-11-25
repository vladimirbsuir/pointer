package dev.pointer.routing;

import dev.pointer.provider.ProviderService;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.ChatRequest;
import dev.pointer.provider.llm.LlmException;
import dev.pointer.provider.llm.OpenAiCompatibleClient;
import dev.pointer.provider.llm.StreamListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RoutedClient {

    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(20);
    private static final Duration PAUSE_SLICE = Duration.ofMillis(50);
    private static final Set<String> RETRYABLE_CODES = Set.of("provider_unreachable", "local_provider_offline");
    private static final int REQUEST_TIMEOUT = 408;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR = 500;
    private static final Logger log = LoggerFactory.getLogger(RoutedClient.class);

    private final OpenAiCompatibleClient client;
    private final ProviderService providers;
    private final Duration retryDelay;

    RoutedClient(
            OpenAiCompatibleClient client,
            ProviderService providers,
            @Value("${pointer.routing.retry-delay:2s}") Duration retryDelay) {
        this.client = client;
        this.providers = providers;
        this.retryDelay = retryDelay;
    }

    public Failover failover(UUID userId, UUID deviceId, boolean smart, SwitchListener switches) {
        return new Failover(userId, deviceId, smart, switches);
    }

    public Failover failover(UUID userId, UUID deviceId, boolean smart) {
        return new Failover(userId, deviceId, smart, (from, to, failure) -> {});
    }

    static boolean isRetryable(LlmException failure) {
        var status = failure.status();
        return status == REQUEST_TIMEOUT
                || status == TOO_MANY_REQUESTS
                || status >= SERVER_ERROR
                || RETRYABLE_CODES.contains(failure.code());
    }

    @FunctionalInterface
    public interface SwitchListener {

        void onSwitch(ResolvedModel from, ResolvedModel to, LlmException failure);
    }

    public final class Failover {

        private final UUID userId;
        private final UUID deviceId;
        private final boolean smart;
        private final SwitchListener switches;
        private final Map<UUID, ModelRouter.RoutedModel> replacements = new ConcurrentHashMap<>();

        private Failover(UUID userId, UUID deviceId, boolean smart, SwitchListener switches) {
            this.userId = userId;
            this.deviceId = deviceId;
            this.smart = smart;
            this.switches = switches;
        }

        public ModelRouter.RoutedModel stream(
                ModelRouter.RoutedModel routed,
                ChatRequest request,
                StreamListener listener,
                Cancellation cancellation) {
            if (!smart) {
                client.stream(routed.model().endpoint(), request, listener, cancellation);
                return routed;
            }
            var primaryId = routed.model().modelId();
            var current = replacements.getOrDefault(primaryId, routed);
            var failure = attempt(current, request, listener, cancellation);
            if (failure == null) {
                return current;
            }
            if (!pause(failure.retryAfter().orElse(retryDelay), cancellation)) {
                throw failure;
            }
            failure = attempt(current, request, listener, cancellation);
            if (failure == null) {
                return current;
            }
            for (var candidate : alternatives(routed, current, request)) {
                log.warn(
                        "Model {} failed ({}), switching to {}",
                        current.model().modelKey(),
                        failure.getMessage(),
                        candidate.model().modelKey());
                switches.onSwitch(current.model(), candidate.model(), failure);
                current = candidate;
                failure = attempt(current, request, listener, cancellation);
                if (failure == null) {
                    remember(primaryId, routed, current);
                    return current;
                }
            }
            throw failure;
        }

        private void remember(UUID primaryId, ModelRouter.RoutedModel routed, ModelRouter.RoutedModel used) {
            if (used == routed) {
                replacements.remove(primaryId);
            } else {
                replacements.put(primaryId, used);
            }
        }

        private LlmException attempt(
                ModelRouter.RoutedModel target,
                ChatRequest request,
                StreamListener listener,
                Cancellation cancellation) {
            var probe = new StartProbe(listener);
            var model = target.model();
            try {
                client.stream(
                        model.endpoint(),
                        new ChatRequest(model.modelKey(), request.messages(), request.tools(), request.maxTokens()),
                        probe,
                        cancellation);
                return null;
            } catch (LlmException failure) {
                if (probe.started || cancellation.isCancelled() || !isRetryable(failure)) {
                    throw failure;
                }
                return failure;
            }
        }

        private List<ModelRouter.RoutedModel> alternatives(
                ModelRouter.RoutedModel routed, ModelRouter.RoutedModel failed, ChatRequest request) {
            var needsTools = !request.tools().isEmpty();
            var needsVision = request.messages().stream()
                    .anyMatch(message -> !message.images().isEmpty());
            var alternatives = new ArrayList<ModelRouter.RoutedModel>();
            if (failed != routed) {
                alternatives.add(routed);
            }
            providers.resolveFallbacks(userId, routed.model().modelId(), deviceId).stream()
                    .filter(model -> !model.modelId().equals(failed.model().modelId()))
                    .filter(model -> !needsTools || model.supportsTools())
                    .filter(model -> !needsVision || model.supportsVision())
                    .map(model -> new ModelRouter.RoutedModel(model, ModelRouter.FALLBACK))
                    .forEach(alternatives::add);
            return alternatives;
        }
    }

    private static boolean pause(Duration requested, Cancellation cancellation) {
        var delay = requested.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : requested;
        var deadline = System.nanoTime() + delay.toNanos();
        try {
            while (!cancellation.isCancelled()) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return true;
                }
                Thread.sleep(Duration.ofNanos(Math.min(remaining, PAUSE_SLICE.toNanos())));
            }
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class StartProbe implements StreamListener {

        private final StreamListener delegate;
        private volatile boolean started;

        private StartProbe(StreamListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onText(String delta) {
            started = true;
            delegate.onText(delta);
        }

        @Override
        public void onReasoning(String delta) {
            started = true;
            delegate.onReasoning(delta);
        }

        @Override
        public void onToolCallDelta(int index, String id, String name, String argumentsDelta) {
            started = true;
            delegate.onToolCallDelta(index, id, name, argumentsDelta);
        }

        @Override
        public void onUsage(Usage usage) {
            delegate.onUsage(usage);
        }
    }
}
