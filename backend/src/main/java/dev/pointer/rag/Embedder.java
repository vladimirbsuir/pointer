package dev.pointer.rag;

import dev.pointer.provider.ProviderService;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.OpenAiCompatibleClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class Embedder {

    static final int BATCH_SIZE = 32;

    private final ProviderService providers;
    private final OpenAiCompatibleClient client;

    ResolvedModel resolve(UUID userId, UUID modelId, UUID deviceId) {
        return providers.resolve(userId, modelId, deviceId);
    }

    List<float[]> embed(ResolvedModel model, List<String> texts, Cancellation cancellation) {
        var vectors = new ArrayList<float[]>(texts.size());
        for (var start = 0; start < texts.size(); start += BATCH_SIZE) {
            if (cancellation.isCancelled()) {
                throw new IndexException("cancelled", "Cancelled");
            }
            var batch = texts.subList(start, Math.min(start + BATCH_SIZE, texts.size()));
            vectors.addAll(client.embed(model.endpoint(), model.modelKey(), batch, cancellation));
        }
        return vectors;
    }
}
