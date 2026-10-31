package dev.pointer.agent;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.session.Item;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class ContextAssembler {

    private static final String BASE_INSTRUCTIONS = """
            You are Pointer, an AI software engineering agent working inside the user's project.
            Be precise and concise. Prefer concrete, working solutions over general advice.
            Format answers in Markdown. Use fenced code blocks with a language tag for code.""";

    private static final Map<String, String> MODE_INSTRUCTIONS = Map.of(
            "ask", "Mode: Ask. Answer questions about the project. Do not propose to change files unless asked.",
            "plan", "Mode: Plan. Investigate and produce a clear, numbered implementation plan. Do not make changes.",
            "agent", "Mode: Agent. Carry out the user's task end to end.");

    private final Clock clock;

    ContextAssembler(Clock clock) {
        this.clock = clock;
    }

    List<ChatMessage> build(String mode, String globalInstructions, List<Map<String, Object>> conversation) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(systemPrompt(mode, globalInstructions)));
        for (var item : startingFromLatestSummary(conversation)) {
            toMessage(item).ifPresent(messages::add);
        }
        return messages;
    }

    private String systemPrompt(String mode, String globalInstructions) {
        var prompt = new StringBuilder(BASE_INSTRUCTIONS)
                .append("\n\n")
                .append(MODE_INSTRUCTIONS.getOrDefault(mode, MODE_INSTRUCTIONS.get("agent")))
                .append("\n\nEnvironment:\n- Date: ")
                .append(LocalDate.now(clock.withZone(ZoneOffset.UTC)))
                .append("\n- OS: Windows\n- Shell: PowerShell");
        if (globalInstructions != null && !globalInstructions.isBlank()) {
            prompt.append("\n\nUser instructions:\n").append(globalInstructions.strip());
        }
        return prompt.toString();
    }

    private static List<Map<String, Object>> startingFromLatestSummary(List<Map<String, Object>> conversation) {
        for (var index = conversation.size() - 1; index >= 0; index--) {
            if (Item.COMPACTION_SUMMARY.equals(conversation.get(index).get("type"))) {
                return conversation.subList(index, conversation.size());
            }
        }
        return conversation;
    }

    private static Optional<ChatMessage> toMessage(Map<String, Object> item) {
        var type = String.valueOf(item.get("type"));
        var text = item.get("text") == null ? "" : String.valueOf(item.get("text"));
        return switch (type) {
            case Item.USER_MESSAGE -> Optional.of(ChatMessage.user(withAttachments(text, item.get("attachments"))));
            case Item.ASSISTANT_MESSAGE -> text.isBlank() ? Optional.empty() : Optional.of(ChatMessage.assistant(text));
            case Item.COMPACTION_SUMMARY ->
                Optional.of(ChatMessage.user("Summary of the earlier conversation:\n" + text));
            default -> Optional.empty();
        };
    }

    private static String withAttachments(String text, Object attachments) {
        if (!(attachments instanceof List<?> list) || list.isEmpty()) {
            return text;
        }
        var result = new StringBuilder();
        for (var attachment : list) {
            if (attachment instanceof Map<?, ?> map) {
                result.append("<file path=\"")
                        .append(map.get("path"))
                        .append("\">\n")
                        .append(map.get("content"))
                        .append("\n</file>\n\n");
            }
        }
        return result.append(text).toString();
    }
}
