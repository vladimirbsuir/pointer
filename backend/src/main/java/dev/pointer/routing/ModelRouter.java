package dev.pointer.routing;

import dev.pointer.provider.ProviderService;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.user.SettingsService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ModelRouter {

    public static final String MAIN = "main";
    public static final String PLAN = "plan";
    public static final String FAST = "fast";
    public static final String FALLBACK = "fallback";
    public static final String EXPLORE = "explore";
    private static final String PLAN_MODE = "plan";
    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final SettingsService settings;
    private final ProviderService providers;

    public ModelPlan plan(UUID userId, boolean smart, ResolvedModel main, String mode, UUID deviceId) {
        var routing = settings.routing(userId);
        var session = new RoutedModel(main, MAIN);
        var configuredExplorer = resolve(userId, routing.exploreModelId(), deviceId)
                .filter(ResolvedModel::supportsTools)
                .map(model -> new RoutedModel(model, EXPLORE));
        if (!smart) {
            return new ModelPlan(session, session, session, configuredExplorer.orElse(new RoutedModel(main, EXPLORE)));
        }
        var step = PLAN_MODE.equals(mode)
                ? resolve(userId, routing.planModelId(), deviceId)
                        .filter(ResolvedModel::supportsTools)
                        .map(model -> new RoutedModel(model, PLAN))
                : Optional.<RoutedModel>empty();
        var fast = resolve(userId, routing.fastModelId(), deviceId).map(model -> new RoutedModel(model, FAST));
        var fastExplorer = fast.map(RoutedModel::model)
                .filter(ResolvedModel::supportsTools)
                .map(model -> new RoutedModel(model, EXPLORE));
        return new ModelPlan(
                session,
                step.orElse(session),
                fast.orElse(session),
                configuredExplorer.or(() -> fastExplorer).orElse(new RoutedModel(main, EXPLORE)));
    }

    public RoutedModel auxiliary(UUID userId, boolean smart, ResolvedModel main, UUID deviceId) {
        return plan(userId, smart, main, null, deviceId).fast();
    }

    private Optional<ResolvedModel> resolve(UUID userId, UUID modelId, UUID deviceId) {
        if (modelId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(providers.resolve(userId, modelId, deviceId));
        } catch (RuntimeException exception) {
            log.debug("Routed model {} is not usable for user {}: {}", modelId, userId, exception.getMessage());
            return Optional.empty();
        }
    }

    public record RoutedModel(ResolvedModel model, String role) {}

    public record ModelPlan(RoutedModel main, RoutedModel step, RoutedModel fast, RoutedModel explore) {

        public RoutedModel compaction(int conversationTokens) {
            var window = fast.model().contextWindow();
            return window == null || window >= conversationTokens * 6 / 5 ? fast : step;
        }
    }
}
