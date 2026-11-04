package dev.pointer.agent;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.session.AttachmentService;
import dev.pointer.session.Item;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
            "Mode: Plan. Investigate the project with read-only tools, then present an implementation plan by calling"
                    + " propose_plan. Do not change files. Ask a short clarifying question instead when the task is"
                    + " ambiguous.",
            "agent",
            "Mode: Agent. Carry out the user's task end to end: inspect, change files, run commands to verify.");

    private static final String COMPACTION_SYSTEM = """
            You summarize a conversation between a user and Pointer, an AI software engineering agent.
            The agent continues the work with only your summary, so it must keep everything needed to proceed.""";

    private static final String COMPACTION_REQUEST = """
            Summarize the conversation so far for the agent that will continue it. Include:
            - the user's goals and requests, including exact wording of requirements;
            - decisions, constraints and preferences;
            - files inspected or changed, with the important details;
            - commands run and their outcomes, including errors;
            - the current state and the remaining work.
            Keep file paths, identifiers and error messages exact. Use concise Markdown. Reply with the summary only.""";

    private static final int CHARS_PER_TOKEN = 4;
    private static final int TOKENS_PER_MESSAGE = 4;
    private static final int TOKENS_PER_IMAGE = 1_000;
    private static final String IMAGE_UNSUPPORTED =
            "[An image was attached here, but the current model does not accept images.]";

    private final Clock clock;
    private final AttachmentService attachments;

    ContextAssembler(Clock clock, AttachmentService attachments) {
        this.clock = clock;
        this.attachments = attachments;
    }

    List<ChatMessage> build(
            String mode,
            String globalInstructions,
            ProjectContext project,
            List<Map<String, Object>> conversation,
            boolean supportsVision) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(systemPrompt(mode, globalInstructions, project)));
        var group = new StepGroup();
        for (var item : activeItems(conversation)) {
            var type = String.valueOf(item.get("type"));
            switch (type) {
                case Item.USER_MESSAGE -> {
                    group.flushInto(messages);
                    messages.add(userMessage(item, supportsVision));
                }
                case Item.COMPACTION_SUMMARY -> {
                    group.flushInto(messages);
                    messages.add(ChatMessage.user("Summary of the earlier conversation:\n" + text(item)));
                }
                case Item.ASSISTANT_MESSAGE, Item.TOOL_CALL, Item.PLAN -> group.add(item, messages);
                default -> {}
            }
        }
        group.flushInto(messages);
        return messages;
    }

    List<ChatMessage> buildCompaction(List<Map<String, Object>> covered) {
        var messages = build("agent", null, null, covered, false);
        messages.set(0, ChatMessage.system(COMPACTION_SYSTEM));
        messages.add(ChatMessage.user(COMPACTION_REQUEST));
        return messages;
    }

    static List<Map<String, Object>> compactable(List<Map<String, Object>> conversation, UUID currentTurnId) {
        var covered = activeItems(conversation).stream()
                .filter(item -> !Objects.equals(item.get("turnId"), currentTurnId))
                .toList();
        var hasNewItems = covered.stream().anyMatch(item -> !Item.COMPACTION_SUMMARY.equals(item.get("type")));
        return hasNewItems ? covered : List.of();
    }

    static int estimateTokens(List<ChatMessage> messages) {
        var chars = 0L;
        var images = 0;
        for (var message : messages) {
            chars += message.content() == null ? 0 : message.content().length();
            for (var call : message.toolCalls()) {
                chars += call.name().length() + call.arguments().length();
            }
            images += message.imageUrls().size();
        }
        return (int) (chars / CHARS_PER_TOKEN) + messages.size() * TOKENS_PER_MESSAGE + images * TOKENS_PER_IMAGE;
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

    static List<Map<String, Object>> activeItems(List<Map<String, Object>> conversation) {
        var attached =
                conversation.stream().filter(item -> item.get("turnId") != null).toList();
        for (var index = attached.size() - 1; index >= 0; index--) {
            var candidate = attached.get(index);
            if (Item.COMPACTION_SUMMARY.equals(candidate.get("type"))
                    && Item.COMPLETED.equals(candidate.get("status"))) {
                var summaryTurn = candidate.get("turnId");
                var active = new ArrayList<Map<String, Object>>();
                active.add(candidate);
                for (var position = 0; position < attached.size(); position++) {
                    var item = attached.get(position);
                    if (position > index || (position < index && summaryTurn.equals(item.get("turnId")))) {
                        active.add(item);
                    }
                }
                return active;
            }
        }
        return attached;
    }

    private static String text(Map<String, Object> item) {
        return item.get("text") == null ? "" : String.valueOf(item.get("text"));
    }

    private ChatMessage userMessage(Map<String, Object> item, boolean supportsVision) {
        var text = withAttachments(text(item), item.get("attachments"));
        var imageIds = imageIds(item.get("attachments"));
        if (imageIds.isEmpty()) {
            return ChatMessage.user(text);
        }
        if (!supportsVision) {
            return ChatMessage.user(text + "\n\n" + IMAGE_UNSUPPORTED);
        }
        var urls = imageIds.stream()
                .map(attachments::dataUrl)
                .flatMap(Optional::stream)
                .toList();
        return ChatMessage.user(text, urls);
    }

    private static List<UUID> imageIds(Object attachments) {
        if (!(attachments instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(attachment -> attachment instanceof Map<?, ?> map && "image".equals(map.get("kind")))
                .map(attachment -> UUID.fromString(String.valueOf(((Map<?, ?>) attachment).get("attachmentId"))))
                .toList();
    }

    private static String withAttachments(String text, Object attachments) {
        if (!(attachments instanceof List<?> list) || list.isEmpty()) {
            return text;
        }
        var result = new StringBuilder();
        for (var attachment : list) {
            if (attachment instanceof Map<?, ?> map && !"image".equals(map.get("kind"))) {
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
