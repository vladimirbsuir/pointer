package dev.pointer.provider.llm;

import java.util.List;
import java.util.Map;

public record ChatRequest(
        String model, List<ChatMessage> messages, List<ToolDefinition> tools, Integer maxTokens, String cacheKey) {

    public ChatRequest(String model, List<ChatMessage> messages, List<ToolDefinition> tools, Integer maxTokens) {
        this(model, messages, tools, maxTokens, null);
    }

    public record ToolDefinition(String name, String description, Map<String, Object> parameters) {

        public ToolDefinition {
            parameters = StableMaps.copy(parameters);
        }
    }

    public static ChatRequest of(String model, List<ChatMessage> messages) {
        return new ChatRequest(model, messages, List.of(), null);
    }

    public ChatRequest withCacheKey(Object key) {
        return new ChatRequest(model, messages, tools, maxTokens, key == null ? null : key.toString());
    }
}
