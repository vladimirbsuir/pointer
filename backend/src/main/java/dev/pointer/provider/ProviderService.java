package dev.pointer.provider;

import dev.pointer.common.crypto.SecretCipher;
import dev.pointer.common.error.ApiException;
import dev.pointer.provider.llm.DirectHttpTransport;
import dev.pointer.provider.llm.Endpoint;
import dev.pointer.provider.llm.LlmException;
import dev.pointer.provider.llm.OpenAiCompatibleClient;
import dev.pointer.provider.llm.RelayTransports;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProviderService {

    private static final int API_KEY_HINT_LENGTH = 4;

    private final ProviderRepository providers;
    private final ProviderModelRepository models;
    private final ModelFallbackStore fallbacks;
    private final SecretCipher cipher;
    private final OpenAiCompatibleClient client;
    private final DirectHttpTransport directTransport;
    private final ObjectProvider<RelayTransports> relayTransports;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ProviderDtos.ProviderResponse> list(UUID userId) {
        var userProviders = providers.findByUserIdOrderByCreatedAt(userId);
        var userModels = models.findByProviderIdInOrderByDisplayName(
                userProviders.stream().map(Provider::getId).toList());
        var modelsByProvider = userModels.stream().collect(Collectors.groupingBy(ProviderModel::getProviderId));
        var fallbackIds =
                fallbacks.listAll(userModels.stream().map(ProviderModel::getId).toList());
        return userProviders.stream()
                .map(provider ->
                        toResponse(provider, modelsByProvider.getOrDefault(provider.getId(), List.of()), fallbackIds))
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
        return toResponse(provider, List.of(), Map.of());
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
        if (request.sendsCacheKey() != null) {
            provider.changeCacheKey(request.sendsCacheKey(), now);
        }
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            provider.setApiKey(null, null, now);
        } else if (request.apiKey() != null && !request.apiKey().isBlank()) {
            applyApiKey(provider, request.apiKey());
        }
        providers.save(provider);
        return withModels(provider);
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
        return withModels(provider);
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
                            null,
                            request.contextWindow(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    clock.instant());
        }
        return toResponse(models.save(model), fallbacks.list(model.getId()));
    }

    @Transactional
    public ProviderDtos.ModelResponse updateModel(
            UUID userId, UUID providerId, UUID modelId, ProviderDtos.UpdateModelRequest request) {
        owned(userId, providerId);
        var model = ownedModel(providerId, modelId);
        model.update(request, clock.instant());
        return toResponse(models.save(model), fallbacks.list(model.getId()));
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
                model.getCachedInputPricePerMtok(),
                model.getOutputPricePerMtok(),
                model.embeddingPrefixes(),
                endpoint(provider, preferredDeviceId),
                model.getCoordinateSystem());
    }

    @Transactional(readOnly = true)
    public ProviderDtos.FallbacksResponse fallbacks(UUID userId, UUID modelId) {
        userModel(userId, modelId);
        return new ProviderDtos.FallbacksResponse(fallbacks.list(modelId));
    }

    @Transactional
    public ProviderDtos.FallbacksResponse replaceFallbacks(
            UUID userId, UUID modelId, ProviderDtos.ReplaceFallbacksRequest request) {
        userModel(userId, modelId);
        var ids = request.modelIds();
        if (ids.contains(modelId)) {
            throw ApiException.badRequest("invalid_fallbacks", "A model cannot be its own fallback");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.badRequest("invalid_fallbacks", "A fallback model is listed twice");
        }
        ids.forEach(id -> userModel(userId, id));
        fallbacks.replace(modelId, ids);
        return new ProviderDtos.FallbacksResponse(fallbacks.list(modelId));
    }

    @Transactional(readOnly = true)
    public List<ResolvedModel> resolveFallbacks(UUID userId, UUID modelId, UUID preferredDeviceId) {
        var resolved = new ArrayList<ResolvedModel>();
        fallbacks
                .list(modelId)
                .forEach(fallbackId ->
                        usable(userId, fallbackId, preferredDeviceId).ifPresent(resolved::add));
        return resolved;
    }

    private Optional<ResolvedModel> usable(UUID userId, UUID modelId, UUID preferredDeviceId) {
        try {
            return Optional.of(resolve(userId, modelId, preferredDeviceId));
        } catch (ApiException | LlmException exception) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<EmbeddingPrefixes> embeddingPrefixes(UUID modelId) {
        return models.findById(modelId).map(ProviderModel::embeddingPrefixes);
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
            return new Endpoint(provider.getBaseUrl(), apiKey, directTransport, provider.sendsCacheKey());
        }
        var relay = relayTransports.getIfAvailable();
        if (relay == null) {
            throw ApiException.unavailable("local_provider_offline", "Local providers are not available");
        }
        return new Endpoint(
                provider.getBaseUrl(),
                apiKey,
                relay.forUser(provider.getUserId(), preferredDeviceId),
                provider.sendsCacheKey());
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

    private ProviderModel userModel(UUID userId, UUID modelId) {
        return models.findById(modelId)
                .filter(model -> providers
                        .findByIdAndUserId(model.getProviderId(), userId)
                        .isPresent())
                .orElseThrow(() -> ApiException.notFound("model_not_found", "Model does not exist"));
    }

    private ProviderModel ownedModel(UUID providerId, UUID modelId) {
        return models.findByIdAndProviderId(modelId, providerId)
                .orElseThrow(() -> ApiException.notFound("model_not_found", "Model does not exist"));
    }

    private static String normalizeUrl(String url) {
        var trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private ProviderDtos.ProviderResponse withModels(Provider provider) {
        var providerModels = models.findByProviderId(provider.getId());
        return toResponse(
                provider,
                providerModels,
                fallbacks.listAll(
                        providerModels.stream().map(ProviderModel::getId).toList()));
    }

    private static ProviderDtos.ProviderResponse toResponse(
            Provider provider, List<ProviderModel> providerModels, Map<UUID, List<UUID>> fallbackIds) {
        return new ProviderDtos.ProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getBaseUrl(),
                provider.isLocal(),
                provider.sendsCacheKey(),
                provider.getApiKeyEncrypted().isPresent(),
                provider.getApiKeyHint(),
                providerModels.stream()
                        .sorted(Comparator.comparing(ProviderModel::getDisplayName))
                        .map(model -> toResponse(model, fallbackIds.getOrDefault(model.getId(), List.of())))
                        .toList());
    }

    private static ProviderDtos.ModelResponse toResponse(ProviderModel model, List<UUID> fallbackIds) {
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
                model.getCachedInputPricePerMtok(),
                model.getOutputPricePerMtok(),
                model.getEmbeddingQueryPrefix(),
                model.getEmbeddingDocumentPrefix(),
                model.getSource(),
                model.isEnabled(),
                fallbackIds,
                model.getCoordinateSystem());
    }
}
