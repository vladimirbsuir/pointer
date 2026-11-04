package dev.pointer.provider.llm;

import java.util.List;

public record ChatMessage(
        Role role, String content, List<ToolCall> toolCalls, String toolCallId, List<String> imageUrls) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public record ToolCall(String id, String name, String arguments) {}

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, List.of(), null, List.of());
    }

    public static ChatMessage user(String content, List<String> imageUrls) {
        return new ChatMessage(Role.USER, content, List.of(), null, imageUrls);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, List.of(), null, List.of());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, List.of(), null, List.of());
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, null, List.of());
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId, List.of());
    }
}
