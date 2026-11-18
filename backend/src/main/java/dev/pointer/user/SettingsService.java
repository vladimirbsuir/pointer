package dev.pointer.user;

import dev.pointer.common.error.ApiException;
import dev.pointer.provider.ProviderService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingsService {

    public static final String CONTEXT_STRATEGY_PATTERN = "summary|masking|hybrid";
    public static final String ESCALATION_PATTERN = "off|on";

    private final UserSettingsRepository settings;
    private final ProviderService providerService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SettingsResponse get(UUID userId) {
        return toResponse(find(userId));
    }

    @Transactional
    public SettingsResponse update(UUID userId, UpdateSettingsRequest request) {
        var userSettings = find(userId);
        var now = clock.instant();
        if (request.globalInstructions() != null) {
            userSettings.changeGlobalInstructions(request.globalInstructions(), now);
        }
        if (Boolean.TRUE.equals(request.clearDefaultModel())) {
            userSettings.changeDefaultModel(null, now);
        } else if (request.defaultModelId() != null) {
            if (!providerService.modelBelongsTo(userId, request.defaultModelId())) {
                throw ApiException.notFound("model_not_found", "Model does not exist");
            }
            userSettings.changeDefaultModel(request.defaultModelId(), now);
        }
        if (request.routingDefault() != null) {
            userSettings.changeRoutingDefault(request.routingDefault(), now);
        }
        if (request.contextStrategyDefault() != null) {
            userSettings.changeContextStrategyDefault(request.contextStrategyDefault(), now);
        }
        if (request.escalationDefault() != null) {
            userSettings.changeEscalationDefault(request.escalationDefault(), now);
        }
        if (Boolean.TRUE.equals(request.clearFastModel())) {
            userSettings.changeFastModel(null, now);
        } else if (request.fastModelId() != null) {
            requireOwnedModel(userId, request.fastModelId());
            userSettings.changeFastModel(request.fastModelId(), now);
        }
        if (Boolean.TRUE.equals(request.clearPlanModel())) {
            userSettings.changePlanModel(null, now);
        } else if (request.planModelId() != null) {
            requireOwnedModel(userId, request.planModelId());
            userSettings.changePlanModel(request.planModelId(), now);
        }
        if (Boolean.TRUE.equals(request.clearExploreModel())) {
            userSettings.changeExploreModel(null, now);
        } else if (request.exploreModelId() != null) {
            requireOwnedModel(userId, request.exploreModelId());
            userSettings.changeExploreModel(request.exploreModelId(), now);
        }
        if (Boolean.TRUE.equals(request.clearEmbeddingModel())) {
            userSettings.changeEmbeddingModel(null, now);
        } else if (request.embeddingModelId() != null) {
            if (!providerService.modelBelongsTo(userId, request.embeddingModelId())) {
                throw ApiException.notFound("model_not_found", "Model does not exist");
            }
            userSettings.changeEmbeddingModel(request.embeddingModelId(), now);
        }
        return toResponse(settings.save(userSettings));
    }

    @Transactional(readOnly = true)
    public UUID defaultModel(UUID userId) {
        var configured = find(userId).getDefaultModelId();
        return configured != null ? configured : providerService.firstEnabledModel(userId);
    }

    @Transactional(readOnly = true)
    public RoutingSettings routing(UUID userId) {
        var userSettings = find(userId);
        return new RoutingSettings(
                userSettings.getRoutingDefault(),
                userSettings.getFastModelId(),
                userSettings.getPlanModelId(),
                userSettings.getExploreModelId());
    }

    @Transactional(readOnly = true)
    public String escalationDefault(UUID userId) {
        return find(userId).getEscalationDefault();
    }

    @Transactional(readOnly = true)
    public String contextStrategyDefault(UUID userId) {
        return find(userId).getContextStrategyDefault();
    }

    @Transactional(readOnly = true)
    public UUID embeddingModel(UUID userId) {
        return find(userId).getEmbeddingModelId();
    }

    private void requireOwnedModel(UUID userId, UUID modelId) {
        if (!providerService.modelBelongsTo(userId, modelId)) {
            throw ApiException.notFound("model_not_found", "Model does not exist");
        }
    }

    private UserSettings find(UUID userId) {
        return settings.findById(userId).orElseGet(() -> settings.save(new UserSettings(userId, clock.instant())));
    }

    private static SettingsResponse toResponse(UserSettings userSettings) {
        return new SettingsResponse(
                userSettings.getGlobalInstructions(),
                userSettings.getDefaultModelId(),
                userSettings.getEmbeddingModelId(),
                userSettings.getRoutingDefault(),
                userSettings.getFastModelId(),
                userSettings.getPlanModelId(),
                userSettings.getExploreModelId(),
                userSettings.getContextStrategyDefault(),
                userSettings.getEscalationDefault());
    }

    public record SettingsResponse(
            String globalInstructions,
            UUID defaultModelId,
            UUID embeddingModelId,
            String routingDefault,
            UUID fastModelId,
            UUID planModelId,
            UUID exploreModelId,
            String contextStrategyDefault,
            String escalationDefault) {}

    public record RoutingSettings(String routingDefault, UUID fastModelId, UUID planModelId, UUID exploreModelId) {}

    public record UpdateSettingsRequest(
            @Size(max = 20_000) String globalInstructions,
            UUID defaultModelId,
            Boolean clearDefaultModel,
            UUID embeddingModelId,
            Boolean clearEmbeddingModel,
            @Pattern(regexp = "off|smart") String routingDefault,
            UUID fastModelId,
            Boolean clearFastModel,
            UUID planModelId,
            Boolean clearPlanModel,
            UUID exploreModelId,
            Boolean clearExploreModel,
            @Pattern(regexp = CONTEXT_STRATEGY_PATTERN) String contextStrategyDefault,
            @Pattern(regexp = ESCALATION_PATTERN) String escalationDefault) {

        public UpdateSettingsRequest(
                String globalInstructions,
                UUID defaultModelId,
                Boolean clearDefaultModel,
                UUID embeddingModelId,
                Boolean clearEmbeddingModel) {
            this(
                    globalInstructions,
                    defaultModelId,
                    clearDefaultModel,
                    embeddingModelId,
                    clearEmbeddingModel,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
