package dev.pointer.provider;

import dev.pointer.common.crypto.SecretCipher;
import dev.pointer.common.error.ApiException;
import dev.pointer.provider.llm.DirectHttpTransport;
import dev.pointer.provider.llm.Endpoint;
import dev.pointer.provider.llm.LlmException;
import dev.pointer.provider.llm.OpenAiCompatibleClient;
import dev.pointer.provider.llm.RelayTransports;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderService {

    private static final int API_KEY_HINT_LENGTH = 4;

    private final ProviderRepository providers;
    private final ProviderModelRepository models;
    private final SecretCipher cipher;
    private final OpenAiCompatibleClient client;
    private final DirectHttpTransport directTransport;
    private final ObjectProvider<RelayTransports> relayTransports;
    private final Clock clock;

    ProviderService(
            ProviderRepository providers,
            ProviderModelRepository models,
            SecretCipher cipher,
            OpenAiCompatibleClient client,
            DirectHttpTransport directTransport,
            ObjectProvider<RelayTransports> relayTransports,
            Clock clock) {
        this.providers = providers;
        this.models = models;
        this.cipher = cipher;
        this.client = client;
        this.directTransport = directTransport;
        this.relayTransports = relayTransports;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProviderDtos.ProviderResponse> list(UUID userId) {
        var userProviders = providers.findByUserIdOrderByCreatedAt(userId);
        var modelsByProvider =
                models
                        .findByProviderIdInOrderByDisplayName(
                                userProviders.stream().map(Provider::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(ProviderModel::getProviderId));
        return userProviders.stream()
                .map(provider -> toResponse(provider, modelsByProvider.getOrDefault(provider.getId(), List.of())))
                .toList();
    }

    @Transactional
    public ProviderDtos.ProviderResponse create(UUID userId, ProviderDtos.CreateProviderRequest request) {
        var now = clock.instant();
        var provider = new Provider(
                userId,
                request.name().strip(),
                normalizeUrl(request.baseUrl()),
                Boolean.TRUE.equals(request.isLocal()),
                now);
        applyApiKey(provider, request.apiKey());
        providers.save(provider);
        return toResponse(provider, List.of());
    }

    @Transactional
    public ProviderDtos.ProviderResponse update(
            UUID userId, UUID providerId, ProviderDtos.UpdateProviderRequest request) {
        var provider = owned(userId, providerId);
        var now = clock.instant();
        if (request.name() != null) {
            provider.rename(request.name().strip(), now);
        }
        if (request.baseUrl() != null) {
            provider.changeBaseUrl(normalizeUrl(request.baseUrl()), now);
        }
        if (request.isLocal() != null) {
            provider.markLocal(request.isLocal(), now);
        }
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            provider.setApiKey(null, null, now);
        } else if (request.apiKey() != null && !request.apiKey().isBlank()) {
            applyApiKey(provider, request.apiKey());
        }
        providers.save(provider);
        return toResponse(provider, models.findByProviderId(providerId));
    }

    @Transactional
    public void delete(UUID userId, UUID providerId) {
        providers.delete(owned(userId, providerId));
    }

    @Transactional
    public ProviderDtos.ProviderResponse refreshModels(UUID userId, UUID providerId, UUID preferredDeviceId) {
        var provider = owned(userId, providerId);
        var discovered = callProvider(() -> client.listModels(endpoint(provider, preferredDeviceId)));
        var existing = models.findByProviderId(providerId).stream()
                .map(ProviderModel::getModelKey)
                .collect(Collectors.toSet());
        var now = clock.instant();
        discovered.stream()
                .filter(key -> !existing.contains(key))
                .forEach(key -> models.save(new ProviderModel(providerId, key, key, ProviderModel.DISCOVERED, now)));
        return toResponse(provider, models.findByProviderId(providerId));
    }

    @Transactional(readOnly = true)
    public ProviderDtos.ConnectionTestResponse test(UUID userId, UUID providerId, UUID preferredDeviceId) {
        var provider = owned(userId, providerId);
        try {
            var discovered = client.listModels(endpoint(provider, preferredDeviceId));
            return new ProviderDtos.ConnectionTestResponse(true, discovered.size(), null, null);
        } catch (LlmException exception) {
            return new ProviderDtos.ConnectionTestResponse(false, 0, exception.code(), exception.getMessage());
        } catch (ApiException exception) {
            return new ProviderDtos.ConnectionTestResponse(false, 0, exception.code(), exception.getMessage());
        }
    }

    @Transactional
    public ProviderDtos.ModelResponse addModel(UUID userId, UUID providerId, ProviderDtos.CreateModelRequest request) {
        owned(userId, providerId);
        var key = request.modelKey().strip();
        if (models.findByProviderId(providerId).stream()
                .anyMatch(model -> model.getModelKey().equals(key))) {
            throw ApiException.conflict("model_exists", "This model is already configured");
        }
        var displayName = request.displayName() == null || request.displayName().isBlank()
                ? key
                : request.displayName().strip();
        var model = new ProviderModel(providerId, key, displayName, ProviderModel.MANUAL, clock.instant());
        if (request.contextWindow() != null) {
            model.update(
                    new ProviderDtos.UpdateModelRequest(
                            null, request.contextWindow(), null, null, null, null, null, null, null),
                    clock.instant());
        }
        return toResponse(models.save(model));
    }

    @Transactional
    public ProviderDtos.ModelResponse updateModel(
            UUID userId, UUID providerId, UUID modelId, ProviderDtos.UpdateModelRequest request) {
        owned(userId, providerId);
        var model = ownedModel(providerId, modelId);
        model.update(request, clock.instant());
        return toResponse(models.save(model));
    }

    @Transactional
    public void deleteModel(UUID userId, UUID providerId, UUID modelId) {
        owned(userId, providerId);
        models.delete(ownedModel(providerId, modelId));
    }

    @Transactional(readOnly = true)
    public ResolvedModel resolve(UUID userId, UUID modelId, UUID preferredDeviceId) {
        var model = models.findById(modelId)
                .orElseThrow(() -> ApiException.notFound("model_not_found", "Model does not exist"));
        var provider = owned(userId, model.getProviderId());
        if (!model.isEnabled()) {
            throw ApiException.badRequest("model_disabled", "Model is disabled");
        }
        return new ResolvedModel(
                model.getId(),
                model.getModelKey(),
                model.getDisplayName(),
                model.getContextWindow(),
                model.supportsTools(),
                model.supportsVision(),
                model.getInputPricePerMtok(),
                model.getOutputPricePerMtok(),
                endpoint(provider, preferredDeviceId));
    }

    @Transactional(readOnly = true)
    public boolean supportsVision(UUID modelId) {
        return models.findById(modelId).map(ProviderModel::supportsVision).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean modelBelongsTo(UUID userId, UUID modelId) {
        return models.findById(modelId)
                .flatMap(model -> providers.findByIdAndUserId(model.getProviderId(), userId))
                .isPresent();
    }

    @Transactional(readOnly = true)
    public UUID firstEnabledModel(UUID userId) {
        return list(userId).stream()
                .flatMap(provider -> provider.models().stream())
                .filter(ProviderDtos.ModelResponse::enabled)
                .min(Comparator.comparing(ProviderDtos.ModelResponse::displayName))
                .map(ProviderDtos.ModelResponse::id)
                .orElse(null);
    }

    private Endpoint endpoint(Provider provider, UUID preferredDeviceId) {
        var apiKey = provider.getApiKeyEncrypted().map(cipher::decrypt).orElse(null);
        if (!provider.isLocal()) {
            return new Endpoint(provider.getBaseUrl(), apiKey, directTransport);
        }
        var relay = relayTransports.getIfAvailable();
        if (relay == null) {
            throw ApiException.unavailable("local_provider_offline", "Local providers are not available");
        }
        return new Endpoint(provider.getBaseUrl(), apiKey, relay.forUser(provider.getUserId(), preferredDeviceId));
    }

    private <T> T callProvider(Supplier<T> call) {
        try {
            return call.get();
        } catch (LlmException exception) {
            throw ApiException.badGateway(exception.code(), exception.getMessage());
        }
    }

    private void applyApiKey(Provider provider, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        var key = apiKey.strip();
        var hint = key.length() <= API_KEY_HINT_LENGTH ? key : key.substring(key.length() - API_KEY_HINT_LENGTH);
        provider.setApiKey(cipher.encrypt(key), hint, clock.instant());
    }

    private Provider owned(UUID userId, UUID providerId) {
        return providers
                .findByIdAndUserId(providerId, userId)
                .orElseThrow(() -> ApiException.notFound("provider_not_found", "Provider does not exist"));
    }

    private ProviderModel ownedModel(UUID providerId, UUID modelId) {
        return models.findByIdAndProviderId(modelId, providerId)
                .orElseThrow(() -> ApiException.notFound("model_not_found", "Model does not exist"));
    }

    private static String normalizeUrl(String url) {
        var trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static ProviderDtos.ProviderResponse toResponse(Provider provider, List<ProviderModel> providerModels) {
        return new ProviderDtos.ProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getBaseUrl(),
                provider.isLocal(),
                provider.getApiKeyEncrypted().isPresent(),
                provider.getApiKeyHint(),
                providerModels.stream()
                        .sorted(Comparator.comparing(ProviderModel::getDisplayName))
                        .map(ProviderService::toResponse)
                        .toList());
    }

    private static ProviderDtos.ModelResponse toResponse(ProviderModel model) {
        return new ProviderDtos.ModelResponse(
                model.getId(),
                model.getProviderId(),
                model.getModelKey(),
                model.getDisplayName(),
                model.getContextWindow(),
                model.getMaxOutputTokens(),
                model.supportsTools(),
                model.supportsVision(),
                model.getInputPricePerMtok(),
                model.getOutputPricePerMtok(),
                model.getSource(),
                model.isEnabled());
    }
}
