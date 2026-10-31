package dev.pointer.provider.llm;

import java.util.List;

public record ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public record ToolCall(String id, String name, String arguments) {}

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, List.of(), null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, List.of(), null);
    }
}
