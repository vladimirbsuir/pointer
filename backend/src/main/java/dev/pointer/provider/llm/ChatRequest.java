package dev.pointer.provider.llm;

import java.util.List;
import java.util.Map;

public record ChatRequest(String model, List<ChatMessage> messages, List<ToolDefinition> tools, Integer maxTokens) {

    public record ToolDefinition(String name, String description, Map<String, Object> parameters) {}

    public static ChatRequest of(String model, List<ChatMessage> messages) {
        return new ChatRequest(model, messages, List.of(), null);
    }
}
