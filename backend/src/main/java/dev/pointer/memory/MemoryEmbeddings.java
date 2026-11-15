package dev.pointer.memory;

import dev.pointer.provider.EmbeddingPrefixes;
import dev.pointer.provider.ProviderService;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.OpenAiCompatibleClient;
import dev.pointer.user.SettingsService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MemoryEmbeddings {

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddings.class);

    private final SettingsService settings;
    private final ProviderService providers;
    private final OpenAiCompatibleClient client;

    Optional<Embedding> document(UUID userId, UUID deviceId, String text) {
        return embed(userId, deviceId, text, false);
    }

    Optional<Embedding> query(UUID userId, UUID deviceId, String text) {
        return embed(userId, deviceId, text, true);
    }

    private Optional<Embedding> embed(UUID userId, UUID deviceId, String text, boolean query) {
        var modelId = settings.embeddingModel(userId);
        if (modelId == null) {
            return Optional.empty();
        }
        try {
            var model = providers.resolve(userId, modelId, deviceId);
            var prefixes = model.embeddingPrefixes() == null ? EmbeddingPrefixes.NONE : model.embeddingPrefixes();
            var input = query ? prefixes.applyToQuery(text) : prefixes.applyToDocument(text);
            var vectors = client.embed(model.endpoint(), model.modelKey(), List.of(input), new Cancellation());
            return vectors.isEmpty() ? Optional.empty() : Optional.of(new Embedding(modelId, vectors.getFirst()));
        } catch (RuntimeException exception) {
            log.debug("Embedding a memory for user {} failed", userId, exception);
            return Optional.empty();
        }
    }

    record Embedding(UUID modelId, float[] vector) {}
}
