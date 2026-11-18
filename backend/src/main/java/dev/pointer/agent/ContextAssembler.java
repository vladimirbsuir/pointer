package dev.pointer.agent;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.provider.llm.TokenEstimate;
import dev.pointer.session.AttachmentService;
import dev.pointer.session.Item;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ContextAssembler {

    static final String NOT_EXECUTED = "The tool call was not executed.";

    private static final String DEFAULT_OS = "Windows";
    private static final String DEFAULT_SHELL = "PowerShell";
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

    private static final String EXPLORE_INSTRUCTIONS = """
            You are a read-only investigator working for Pointer, an AI software engineering agent, inside the \
            user's project. You receive one task from the agent. Find the answer with the available tools: list \
            directories, glob and grep to locate code, read only the parts of files you need. You cannot change \
            files or run commands. All paths are relative to the project root.
            Be economical: every tool result stays in your context. Answer exactly what the task asks and stop as \
            soon as you can answer it; do not survey related code, tests, configuration or documentation unless \
            the task asks for them, and do not re-check what you have already seen. Use narrow grep patterns \
            limited by path or glob, and read line ranges instead of whole files once you know where to look.
            You have at most %d turns and should need far fewer: in each turn call every tool you can already \
            name at once, for example several greps or several file ranges together, instead of one call per turn.
            When you have the answer, reply with a concise report in Markdown and no tool calls: what you found, \
            with file paths and line numbers for every claim, short quotes of the decisive lines, and what you \
            could not find or verify. The agent sees only this report.""";

    private static final String COMPACTION_SYSTEM = """
            You summarize a conversation between a user and Pointer, an AI software engineering agent.
            The agent continues the work with only your summary, so it must keep everything needed to proceed.""";

    private static final String SUMMARY_CONTENTS = """
            Include:
            - the user's goals and requests, including exact wording of requirements;
            - decisions, constraints and preferences;
            - files inspected or changed, with the important details;
            - commands run and their outcomes, including errors;
            - the current state and the remaining work.
            Keep file paths, identifiers and error messages exact. Use concise Markdown. Reply with the summary only.""";

    private static final String COMPACTION_REQUEST =
            "Summarize the conversation so far for the agent that will continue it. " + SUMMARY_CONTENTS;

    private static final String CONTINUATION_REQUEST = """
            The message that starts with "Summary of the earlier conversation" will be kept word for word in \
            front of your summary. Summarize only the conversation after that message for the agent that will \
            continue it, without repeating what the earlier summary already says. """ + SUMMARY_CONTENTS;

    private static final String CACHE_SAFE_PREAMBLE = """
            Pause the task and do not call any tools in this reply: the conversation is about to be replaced \
            by your summary.
            """;

    private static final String CONTEXT_OPEN =
            "<context>\nCurrent environment and memory. This block replaces any earlier context block.\n";
    private static final String CONTEXT_CLOSE = "\n</context>\n\n";
    static final int MIN_MASKED_RESULT_CHARS = 200;
    private static final int MAX_PLACEHOLDER_ARGUMENT_CHARS = 80;
    private static final String IMAGE_UNSUPPORTED =
            "[An image was attached here, but the current model does not accept images.]";

    private final Clock clock;
    private final AttachmentService attachments;

    List<ChatMessage> build(
            String mode,
            String globalInstructions,
            ProjectContext project,
            List<Map<String, Object>> conversation,
            SequencedMap<UUID, String> snapshots,
            MaskPolicy mask,
            boolean supportsVision) {
        var active = activeItems(conversation);
        return render(
                systemPrompt(mode, globalInstructions, project),
                active,
                carriedSnapshot(active, snapshots),
                snapshots,
                mask.boundary(countResults(active)),
                supportsVision);
    }

    List<ChatMessage> buildCacheSafeCompaction(
            String mode,
            String globalInstructions,
            ProjectContext project,
            List<Map<String, Object>> conversation,
            SequencedMap<UUID, String> snapshots,
            MaskPolicy mask,
            boolean supportsVision,
            UUID currentTurnId,
            boolean continuesSummary) {
        var active = activeItems(conversation);
        var covered = active.stream()
                .filter(item -> !Objects.equals(item.get("turnId"), currentTurnId))
                .toList();
        var messages = render(
                systemPrompt(mode, globalInstructions, project),
                covered,
                carriedSnapshot(active, snapshots),
                snapshots,
                mask.boundary(countResults(active)),
                supportsVision);
        messages.add(ChatMessage.user(CACHE_SAFE_PREAMBLE + compactionRequest(continuesSummary)));
        return messages;
    }

    private List<ChatMessage> render(
            String systemPrompt,
            List<Map<String, Object>> items,
            String carried,
            SequencedMap<UUID, String> snapshots,
            int maskBoundary,
            boolean supportsVision) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(systemPrompt));
        var pending = new LinkedHashMap<>(snapshots);
        var group = new StepGroup(new ResultMask(maskBoundary));
        for (var item : items) {
            var type = String.valueOf(item.get("type"));
            switch (type) {
                case Item.USER_MESSAGE -> {
                    group.flushInto(messages);
                    messages.add(userMessage(item, pending.remove(item.get("turnId")), supportsVision));
                }
                case Item.COMPACTION_SUMMARY -> {
                    group.flushInto(messages);
                    messages.add(ChatMessage.user(
                            withSnapshot(carried, "Summary of the earlier conversation:\n" + text(item))));
                }
                case Item.ASSISTANT_MESSAGE, Item.TOOL_CALL, Item.PLAN -> group.add(item, messages);
                default -> {}
            }
        }
        group.flushInto(messages);
        return messages;
    }

    List<ChatMessage> buildExplore(String request, ProjectContext project, List<Map<String, Object>> nestedItems) {
        var messages = new ArrayList<ChatMessage>();
        var prompt = new StringBuilder(EXPLORE_INSTRUCTIONS.formatted(ExploreAgent.MAX_STEPS))
                .append("\n\nEnvironment:\n- OS: ")
                .append(project == null || project.os() == null ? DEFAULT_OS : project.os());
        if (project != null) {
            prompt.append("\n- Project: ").append(project.name());
        }
        messages.add(ChatMessage.system(prompt.toString()));
        messages.add(ChatMessage.user(request));
        var group = new StepGroup(new ResultMask(0));
        nestedItems.forEach(item -> group.add(item, messages));
        group.flushInto(messages);
        return messages;
    }

    List<ChatMessage> buildCompaction(List<Map<String, Object>> covered, MaskPolicy mask, boolean continuesSummary) {
        var messages = build("agent", null, null, covered, new LinkedHashMap<>(), mask, false);
        messages.set(0, ChatMessage.system(COMPACTION_SYSTEM));
        messages.add(ChatMessage.user(compactionRequest(continuesSummary)));
        return messages;
    }

    static String carriedSummary(List<Map<String, Object>> covered, int maxTokens) {
        if (covered.isEmpty()
                || !Item.COMPACTION_SUMMARY.equals(covered.getFirst().get("type"))) {
            return null;
        }
        var summary = text(covered.getFirst());
        return summary.isBlank() || TokenEstimate.text(summary) > maxTokens ? null : summary;
    }

    private static String compactionRequest(boolean continuesSummary) {
        return continuesSummary ? CONTINUATION_REQUEST : COMPACTION_REQUEST;
    }

    static List<Map<String, Object>> compactable(List<Map<String, Object>> conversation, UUID currentTurnId) {
        var covered = activeItems(conversation).stream()
                .filter(item -> !Objects.equals(item.get("turnId"), currentTurnId))
                .toList();
        var hasNewItems = covered.stream().anyMatch(item -> !Item.COMPACTION_SUMMARY.equals(item.get("type")));
        return hasNewItems ? covered : List.of();
    }

    static int estimateTokens(List<ChatMessage> messages) {
        return TokenEstimate.messages(messages);
    }

    String snapshot(ProjectContext project) {
        var text = new StringBuilder("- Date: ").append(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        if (project != null && project.gitBranch() != null) {
            text.append("\n- Git branch: ").append(project.gitBranch());
        }
        if (project != null && project.memory() != null && !project.memory().isBlank()) {
            text.append("\n\n").append(project.memory().strip());
        }
        return text.toString();
    }

    private static String carriedSnapshot(List<Map<String, Object>> active, SequencedMap<UUID, String> snapshots) {
        var activeTurns = active.stream()
                .filter(item -> Item.USER_MESSAGE.equals(item.get("type")))
                .map(item -> item.get("turnId"))
                .toList();
        if (!activeTurns.isEmpty() && snapshots.containsKey(activeTurns.getFirst())) {
            return null;
        }
        return snapshots.entrySet().stream()
                .filter(entry -> !activeTurns.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .reduce((earlier, later) -> later)
                .orElse(null);
    }

    static int countResults(List<Map<String, Object>> items) {
        return (int) items.stream()
                .filter(item -> Item.TOOL_CALL.equals(item.get("type")) && item.get("providerCallId") != null)
                .count();
    }

    static String placeholder(Map<String, Object> call, String result) {
        var arguments = String.valueOf(call.getOrDefault("argumentsJson", "{}"));
        if (arguments.length() > MAX_PLACEHOLDER_ARGUMENT_CHARS) {
            arguments = arguments.substring(0, MAX_PLACEHOLDER_ARGUMENT_CHARS) + "...";
        }
        return "[Result of " + call.get("toolName") + "(" + arguments + ") omitted: "
                + result.lines().count() + " lines. Run the tool again if you need it.]";
    }

    private static String withSnapshot(String snapshot, String text) {
        return snapshot == null ? text : CONTEXT_OPEN + snapshot + CONTEXT_CLOSE + text;
    }

    private String systemPrompt(String mode, String globalInstructions, ProjectContext project) {
        var prompt = new StringBuilder(BASE_INSTRUCTIONS)
                .append("\n\n")
                .append(MODE_INSTRUCTIONS.getOrDefault(mode, MODE_INSTRUCTIONS.get("agent")))
                .append("\n\nEnvironment:\n- OS: ")
                .append(project == null || project.os() == null ? DEFAULT_OS : project.os())
                .append("\n- Shell: ")
                .append(project == null || project.shell() == null ? DEFAULT_SHELL : project.shell());
        if (project != null) {
            prompt.append("\n- Project: ").append(project.name());
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

    private ChatMessage userMessage(Map<String, Object> item, String snapshot, boolean supportsVision) {
        var text = withSnapshot(snapshot, withAttachments(text(item), item.get("attachments")));
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

    private static final class ResultMask {

        private final int boundary;
        private int next;

        ResultMask(int boundary) {
            this.boundary = boundary;
        }

        String render(Map<String, Object> call, String result) {
            var index = Item.TOOL_CALL.equals(call.get("type")) ? next++ : Integer.MAX_VALUE;
            if (index >= boundary || result.length() < MIN_MASKED_RESULT_CHARS) {
                return result;
            }
            return placeholder(call, result);
        }
    }

    private static final class StepGroup {

        private final ResultMask mask;
        private String key;
        private final StringBuilder text = new StringBuilder();
        private final List<Map<String, Object>> calls = new ArrayList<>();

        StepGroup(ResultMask mask) {
            this.mask = mask;
        }

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
                            mask.render(call, result == null ? NOT_EXECUTED : String.valueOf(result))));
                }
            }
            key = null;
            text.setLength(0);
            calls.clear();
        }
    }

    record ProjectContext(
            String name,
            String gitBranch,
            String rulesFile,
            String rulesContent,
            String os,
            String shell,
            String memory) {

        ProjectContext(String name, String gitBranch, String rulesFile, String rulesContent, String os, String shell) {
            this(name, gitBranch, rulesFile, rulesContent, os, shell, null);
        }

        ProjectContext(String name) {
            this(name, null, null, null, null, null, null);
        }

        ProjectContext withMemory(String section) {
            return new ProjectContext(name, gitBranch, rulesFile, rulesContent, os, shell, section);
        }
    }
}
