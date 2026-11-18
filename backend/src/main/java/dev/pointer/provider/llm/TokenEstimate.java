package dev.pointer.provider.llm;

import java.util.List;

public final class TokenEstimate {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int TOKENS_PER_MESSAGE = 4;
    private static final int TOKENS_PER_IMAGE = 1_000;

    private TokenEstimate() {}

    public static int messages(List<ChatMessage> messages) {
        var chars = 0L;
        var images = 0;
        for (var message : messages) {
            chars += chars(message);
            images += message.imageUrls().size();
        }
        return (int) (chars / CHARS_PER_TOKEN) + messages.size() * TOKENS_PER_MESSAGE + images * TOKENS_PER_IMAGE;
    }

    public static int message(ChatMessage message) {
        return messages(List.of(message));
    }

    private static long chars(ChatMessage message) {
        var chars = message.content() == null ? 0L : message.content().length();
        for (var call : message.toolCalls()) {
            chars += call.name().length() + call.arguments().length();
        }
        return chars;
    }

    public static int tools(List<ChatRequest.ToolDefinition> tools) {
        var chars = 0L;
        for (var tool : tools) {
            chars += tool.name().length()
                    + String.valueOf(tool.description()).length()
                    + String.valueOf(tool.parameters()).length();
        }
        return (int) (chars / CHARS_PER_TOKEN);
    }

    public static int text(String text) {
        return text == null ? 0 : text.length() / CHARS_PER_TOKEN;
    }
}
