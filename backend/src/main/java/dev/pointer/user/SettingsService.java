package dev.pointer.user;

import dev.pointer.common.error.ApiException;
import dev.pointer.provider.ProviderService;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingsService {

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
    public UUID embeddingModel(UUID userId) {
        return find(userId).getEmbeddingModelId();
    }

    private UserSettings find(UUID userId) {
        return settings.findById(userId).orElseGet(() -> settings.save(new UserSettings(userId, clock.instant())));
    }

    private static SettingsResponse toResponse(UserSettings userSettings) {
        return new SettingsResponse(
                userSettings.getGlobalInstructions(),
                userSettings.getDefaultModelId(),
                userSettings.getEmbeddingModelId());
    }

    public record SettingsResponse(String globalInstructions, UUID defaultModelId, UUID embeddingModelId) {}

    public record UpdateSettingsRequest(
            @Size(max = 20_000) String globalInstructions,
            UUID defaultModelId,
            Boolean clearDefaultModel,
            UUID embeddingModelId,
            Boolean clearEmbeddingModel) {}
}
