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
    private static final String PLAN_MODE = "plan";
    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final SettingsService settings;
    private final ProviderService providers;

    public ModelPlan plan(UUID userId, boolean smart, ResolvedModel main, String mode, UUID deviceId) {
        if (!smart) {
            return ModelPlan.single(main);
        }
        var routing = settings.routing(userId);
        var step = PLAN_MODE.equals(mode)
                ? resolve(userId, routing.planModelId(), deviceId)
                        .filter(ResolvedModel::supportsTools)
                        .map(model -> new RoutedModel(model, PLAN))
                : Optional.<RoutedModel>empty();
        var fast = resolve(userId, routing.fastModelId(), deviceId).map(model -> new RoutedModel(model, FAST));
        return new ModelPlan(
                new RoutedModel(main, MAIN),
                step.orElse(new RoutedModel(main, MAIN)),
                fast.orElse(new RoutedModel(main, MAIN)));
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

    public record ModelPlan(RoutedModel main, RoutedModel step, RoutedModel fast) {

        static ModelPlan single(ResolvedModel model) {
            var main = new RoutedModel(model, MAIN);
            return new ModelPlan(main, main, main);
        }

        public RoutedModel compaction(int conversationTokens) {
            var window = fast.model().contextWindow();
            return window == null || window >= conversationTokens * 6 / 5 ? fast : step;
        }
    }
}
