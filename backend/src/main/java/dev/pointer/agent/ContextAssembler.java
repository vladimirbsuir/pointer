package dev.pointer.agent;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.session.Item;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ContextAssembler {

    static final String NOT_EXECUTED = "The tool call was not executed.";

    private static final String BASE_INSTRUCTIONS = """
            You are Pointer, an AI software engineering agent working inside the user's project on their computer.
            Be precise and concise. Prefer concrete, working solutions over general advice.
            Use the available tools to inspect the project before answering questions about it or changing it.
            Read a file before editing it. Make focused edits with edit_file instead of rewriting whole files.
            All paths are relative to the project root. Never assume file contents you have not read.
            Format answers in Markdown. Use fenced code blocks with a language tag for code.""";

    private static final Map<String, String> MODE_INSTRUCTIONS = Map.of(
            "ask",
            "Mode: Ask. Answer questions about the project using read-only tools. Do not change files.",
            "plan",
            "Mode: Plan. Investigate with read-only tools and produce a clear, numbered implementation plan."
                    + " Do not change files.",
            "agent",
            "Mode: Agent. Carry out the user's task end to end: inspect, change files, run commands to verify.");

    private final Clock clock;

    ContextAssembler(Clock clock) {
        this.clock = clock;
    }

    List<ChatMessage> build(
            String mode, String globalInstructions, ProjectContext project, List<Map<String, Object>> conversation) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(systemPrompt(mode, globalInstructions, project)));
        var group = new StepGroup();
        for (var item : startingFromLatestSummary(conversation)) {
            var type = String.valueOf(item.get("type"));
            switch (type) {
                case Item.USER_MESSAGE -> {
                    group.flushInto(messages);
                    messages.add(ChatMessage.user(withAttachments(text(item), item.get("attachments"))));
                }
                case Item.COMPACTION_SUMMARY -> {
                    group.flushInto(messages);
                    messages.add(ChatMessage.user("Summary of the earlier conversation:\n" + text(item)));
                }
                case Item.ASSISTANT_MESSAGE, Item.TOOL_CALL -> group.add(item, messages);
                default -> {}
            }
        }
        group.flushInto(messages);
        return messages;
    }

    private String systemPrompt(String mode, String globalInstructions, ProjectContext project) {
        var prompt = new StringBuilder(BASE_INSTRUCTIONS)
                .append("\n\n")
                .append(MODE_INSTRUCTIONS.getOrDefault(mode, MODE_INSTRUCTIONS.get("agent")))
                .append("\n\nEnvironment:\n- Date: ")
                .append(LocalDate.now(clock.withZone(ZoneOffset.UTC)))
                .append("\n- OS: Windows\n- Shell: PowerShell");
        if (project != null) {
            prompt.append("\n- Project: ").append(project.name());
            if (project.gitBranch() != null) {
                prompt.append("\n- Git branch: ").append(project.gitBranch());
            }
        }
        if (globalInstructions != null && !globalInstructions.isBlank()) {
            prompt.append("\n\nUser instructions:\n").append(globalInstructions.strip());
        }
        if (project != null
                && project.rulesContent() != null
                && !project.rulesContent().isBlank()) {
            prompt.append("\n\nProject rules from ")
                    .append(project.rulesFile())
                    .append(":\n")
                    .append(project.rulesContent().strip());
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

    private static String text(Map<String, Object> item) {
        return item.get("text") == null ? "" : String.valueOf(item.get("text"));
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

    private static final class StepGroup {

        private String key;
        private final StringBuilder text = new StringBuilder();
        private final List<Map<String, Object>> calls = new ArrayList<>();

        void add(Map<String, Object> item, List<ChatMessage> messages) {
            var itemKey = item.get("turnId") + ":" + item.get("step");
            if (item.get("step") == null || !Objects.equals(itemKey, key)) {
                flushInto(messages);
                key = itemKey;
            }
            if (Item.ASSISTANT_MESSAGE.equals(item.get("type"))) {
                text.append(text(item));
            } else if (item.get("providerCallId") != null) {
                calls.add(item);
            }
        }

        void flushInto(List<ChatMessage> messages) {
            if (key == null) {
                return;
            }
            if (calls.isEmpty()) {
                if (!text.isEmpty()) {
                    messages.add(ChatMessage.assistant(text.toString()));
                }
            } else {
                messages.add(ChatMessage.assistant(
                        text.toString(),
                        calls.stream()
                                .map(call -> new ChatMessage.ToolCall(
                                        String.valueOf(call.get("providerCallId")),
                                        String.valueOf(call.get("toolName")),
                                        String.valueOf(call.getOrDefault("argumentsJson", "{}"))))
                                .toList()));
                for (var call : calls) {
                    var result = call.get("resultText");
                    messages.add(ChatMessage.toolResult(
                            String.valueOf(call.get("providerCallId")),
                            result == null ? NOT_EXECUTED : String.valueOf(result)));
                }
            }
            key = null;
            text.setLength(0);
            calls.clear();
        }
    }

    record ProjectContext(String name, String gitBranch, String rulesFile, String rulesContent) {}
}
