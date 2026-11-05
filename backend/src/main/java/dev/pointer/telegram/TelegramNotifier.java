package dev.pointer.telegram;

import static dev.pointer.telegram.TelegramFormat.escape;
import static dev.pointer.telegram.TelegramTexts.text;

import dev.pointer.session.SessionEventListener;
import dev.pointer.session.SessionEventRecord;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
class TelegramNotifier implements SessionEventListener {

    static final Duration EDIT_INTERVAL = Duration.ofMillis(1500);

    private static final int MAX_PROGRESS_LINES = 12;
    private static final int PREVIEW_LIMIT = 2500;
    private static final int SUMMARY_LIMIT = 60;
    private static final Set<String> KINDS = Set.of(
            "turn.started",
            "turn.completed",
            "item.created",
            "item.updated",
            "item.completed",
            "approval.requested",
            "approval.resolved");
    private static final Set<String> ITEM_TYPES = Set.of("tool_call", "assistant_message", "plan");
    private static final List<String> SUMMARY_KEYS = List.of("path", "command", "pattern", "url", "query", "name");

    private final TelegramProperties properties;
    private final TelegramLinkService links;
    private final TelegramDispatcher dispatcher;
    private final TelegramApi api;
    private final TelegramDirectory directory;
    private final JsonMapper json;
    private final Clock clock;

    private final Map<UUID, TurnProgress> turns = new HashMap<>();
    private final Map<UUID, Prompt> approvals = new ConcurrentHashMap<>();
    private final Map<UUID, Prompt> plans = new ConcurrentHashMap<>();

    @PostConstruct
    void start() {
        if (properties.enabled()) {
            dispatcher.every(EDIT_INTERVAL, this::flushProgress);
        }
    }

    @Override
    public void onSessionEvent(UUID userId, SessionEventRecord record) {
        if (!properties.enabled()) {
            return;
        }
        var event = record.event();
        var kind = String.valueOf(event.get("kind"));
        if (!KINDS.contains(kind)) {
            return;
        }
        if (kind.startsWith("item.")
                && !ITEM_TYPES.contains(String.valueOf(item(event).get("type")))) {
            return;
        }
        dispatcher.submit(() -> handle(userId, record.sessionId(), kind, event));
    }

    Optional<Prompt> approval(UUID approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    Optional<Map.Entry<UUID, Prompt>> approvalByMessage(long chatId, long messageId) {
        return approvals.entrySet().stream()
                .filter(entry ->
                        entry.getValue().chatId() == chatId && entry.getValue().messageId() == messageId)
                .findFirst();
    }

    Optional<Prompt> plan(UUID itemId) {
        return Optional.ofNullable(plans.get(itemId));
    }

    void sessionSelected(UUID userId, UUID sessionId) {
        turns.values().stream()
                .filter(progress -> progress.userId.equals(userId) && progress.sessionId.equals(sessionId))
                .filter(progress -> !progress.live)
                .forEach(progress -> {
                    progress.live = true;
                    progress.messageId = api.sendMessage(progress.chatId, renderProgress(progress, null), null);
                    progress.lastEdit = clock.instant();
                });
    }

    private void handle(UUID userId, UUID sessionId, String kind, Map<String, Object> event) {
        var link = links.forUser(userId);
        if (link.isEmpty()) {
            return;
        }
        var chatId = link.get().chatId();
        switch (kind) {
            case "turn.started" ->
                turnStarted(userId, sessionId, chatId, link.get().isSelected(sessionId), event);
            case "turn.completed" -> turnCompleted(event);
            case "approval.requested" -> approvalRequested(userId, sessionId, chatId, event);
            case "approval.resolved" -> approvalResolved(userId, event);
            default -> itemChanged(userId, sessionId, chatId, kind, item(event));
        }
    }

    private void turnStarted(UUID userId, UUID sessionId, long chatId, boolean live, Map<String, Object> event) {
        if (!"message".equals(event.get("turnKind"))) {
            return;
        }
        var locale = directory.locale(userId);
        var progress = new TurnProgress(userId, sessionId, chatId, locale, directory.title(userId, locale, sessionId));
        turns.put(uuid(event.get("turnId")), progress);
        if (live) {
            progress.live = true;
            progress.messageId = api.sendMessage(chatId, renderProgress(progress, null), null);
            progress.lastEdit = clock.instant();
        }
    }

    private void turnCompleted(Map<String, Object> event) {
        var progress = turns.remove(uuid(event.get("turnId")));
        if (progress == null) {
            return;
        }
        var reason = String.valueOf(event.get("reason"));
        var error = errorMessage(event.get("error"));
        if (progress.live && progress.messageId != null) {
            api.editMessage(progress.chatId, progress.messageId, renderProgress(progress, reason), null);
        }
        if (progress.lastAnswer != null) {
            sendAnswer(progress);
        }
        if (!progress.live && "failed".equals(reason)) {
            var html = "<b>" + escape(progress.title) + "</b>\n" + text(progress.locale, "turnFailed", escape(error));
            api.sendMessage(progress.chatId, html, openSessionKeyboard(progress.locale, progress.sessionId));
        }
        if (progress.live && "failed".equals(reason) && progress.lastAnswer == null) {
            api.sendMessage(progress.chatId, text(progress.locale, "turnFailed", escape(error)), null);
        }
    }

    private void sendAnswer(TurnProgress progress) {
        var messages = new ArrayList<>(TelegramFormat.markdownMessages(progress.lastAnswer));
        if (messages.isEmpty()) {
            return;
        }
        if (!progress.live) {
            messages.set(0, "<b>" + escape(progress.title) + "</b>\n\n" + messages.getFirst());
        }
        for (var index = 0; index < messages.size(); index++) {
            var last = index == messages.size() - 1;
            var keyboard = last && !progress.live ? openSessionKeyboard(progress.locale, progress.sessionId) : null;
            api.sendMessage(progress.chatId, messages.get(index), keyboard);
        }
    }

    private void itemChanged(UUID userId, UUID sessionId, long chatId, String kind, Map<String, Object> item) {
        var type = String.valueOf(item.get("type"));
        var progress = turns.get(uuid(item.get("turnId")));
        switch (type) {
            case "tool_call" -> {
                if (progress != null) {
                    progress.lines.put(uuid(item.get("id")), toolLine(item));
                    progress.dirty = true;
                }
            }
            case "assistant_message" -> {
                var content = item.get("text");
                if (progress != null && "item.completed".equals(kind) && content instanceof String answer) {
                    if (!answer.isBlank()) {
                        progress.lastAnswer = answer;
                    }
                }
            }
            default -> planChanged(userId, sessionId, chatId, kind, item);
        }
    }

    private void planChanged(UUID userId, UUID sessionId, long chatId, String kind, Map<String, Object> item) {
        var itemId = uuid(item.get("id"));
        var approval = String.valueOf(item.get("approval"));
        if ("item.completed".equals(kind) && "pending".equals(approval) && !plans.containsKey(itemId)) {
            var locale = directory.locale(userId);
            var html = renderPlan(directory.title(userId, locale, sessionId), locale, item);
            var keyboard = TelegramTypes.Keyboard.row(new TelegramTypes.Button(
                    text(locale, "button.approvePlan"), CallbackData.encode(CallbackData.PLAN, itemId)));
            plans.put(itemId, new Prompt(sessionId, chatId, api.sendMessage(chatId, html, keyboard), html, locale));
            return;
        }
        if (!"pending".equals(approval)) {
            var prompt = plans.remove(itemId);
            if (prompt != null) {
                var status = text(prompt.locale(), "approved".equals(approval) ? "plan.approved" : "plan.rejected");
                api.editMessage(prompt.chatId(), prompt.messageId(), prompt.html() + "\n\n" + status, null);
            }
        }
    }

    private void approvalRequested(UUID userId, UUID sessionId, long chatId, Map<String, Object> event) {
        var approvalId = uuid(event.get("approvalId"));
        var locale = directory.locale(userId);
        var preview = map(event.get("preview"));
        var html = "<b>" + escape(directory.title(userId, locale, sessionId)) + "</b>\n"
                + text(locale, "approval.title") + "\n\n" + renderPreview(locale, preview);
        var approve = new TelegramTypes.Button(
                text(locale, "button.approve"),
                CallbackData.encode(CallbackData.APPROVAL, approvalId, CallbackData.APPROVE_ONCE));
        var deny = new TelegramTypes.Button(
                text(locale, "button.deny"), CallbackData.encode(CallbackData.APPROVAL, approvalId, CallbackData.DENY));
        var rows = new ArrayList<List<TelegramTypes.Button>>();
        rows.add(List.of(approve, deny));
        if (!"tool_proposal".equals(preview.get("kind"))) {
            rows.add(List.of(new TelegramTypes.Button(
                    text(locale, "button.approveSession"),
                    CallbackData.encode(CallbackData.APPROVAL, approvalId, CallbackData.APPROVE_SESSION))));
        }
        var messageId = api.sendMessage(chatId, html, new TelegramTypes.Keyboard(rows));
        approvals.put(approvalId, new Prompt(sessionId, chatId, messageId, html, locale));
    }

    private void approvalResolved(UUID userId, Map<String, Object> event) {
        var prompt = approvals.remove(uuid(event.get("approvalId")));
        if (prompt == null) {
            return;
        }
        var decision = String.valueOf(event.get("decision"));
        var key = switch (decision) {
            case "approved" -> "session".equals(event.get("scope")) ? "approval.approvedSession" : "approval.approved";
            case "denied" -> "approval.denied";
            default -> "approval.cancelled";
        };
        var html = prompt.html() + "\n\n" + text(prompt.locale(), key);
        var message = event.get("message");
        if ("denied".equals(decision) && message instanceof String note && !note.isBlank()) {
            html += ": " + escape(note);
        }
        api.editMessage(prompt.chatId(), prompt.messageId(), html, null);
    }

    private void flushProgress() {
        var now = clock.instant();
        turns.values().stream()
                .filter(progress -> progress.live && progress.dirty && progress.messageId != null)
                .filter(progress -> !now.isBefore(progress.lastEdit.plus(EDIT_INTERVAL)))
                .forEach(progress -> {
                    progress.dirty = false;
                    progress.lastEdit = now;
                    api.editMessage(progress.chatId, progress.messageId, renderProgress(progress, null), null);
                });
    }

    private String renderProgress(TurnProgress progress, String reason) {
        var html = new StringBuilder("<b>").append(escape(progress.title)).append("</b>\n");
        html.append(text(progress.locale, reason == null ? "progress.working" : progressKey(reason)));
        var lines = new ArrayList<>(progress.lines.values());
        if (lines.size() > MAX_PROGRESS_LINES) {
            html.append('\n').append(text(progress.locale, "progress.earlier", lines.size() - MAX_PROGRESS_LINES));
            lines = new ArrayList<>(lines.subList(lines.size() - MAX_PROGRESS_LINES, lines.size()));
        }
        lines.forEach(line -> html.append('\n').append(line));
        return html.toString();
    }

    private static String progressKey(String reason) {
        var key = "progress." + reason;
        return TelegramTexts.has(key) ? key : "progress.completed";
    }

    private static String toolLine(Map<String, Object> item) {
        var status = String.valueOf(item.get("toolStatus"));
        var icon = switch (status) {
            case "succeeded" -> "✓";
            case "failed" -> "✗";
            case "denied" -> "🚫";
            case "cancelled" -> "⏹";
            case "awaiting_approval" -> "🔐";
            default -> "⏳";
        };
        var line = new StringBuilder(icon).append(" <code>").append(escape(String.valueOf(item.get("toolName"))));
        line.append("</code>");
        var summary = summary(map(item.get("arguments")));
        if (!summary.isEmpty()) {
            line.append(' ').append(escape(summary));
        }
        return line.toString();
    }

    private static String summary(Map<String, Object> arguments) {
        return SUMMARY_KEYS.stream()
                .map(arguments::get)
                .filter(value -> value instanceof String text && !text.isBlank())
                .map(value -> TelegramFormat.shorten((String) value, SUMMARY_LIMIT))
                .findFirst()
                .orElse("");
    }

    private String renderPreview(String locale, Map<String, Object> preview) {
        var kind = String.valueOf(preview.get("kind"));
        return switch (kind) {
            case "command" -> {
                var html = text(locale, "approval.command") + "\n"
                        + TelegramFormat.code(string(preview, "command"), PREVIEW_LIMIT);
                var directory = string(preview, "workingDirectory");
                yield directory.isEmpty() || ".".equals(directory)
                        ? html
                        : html + "\n<code>" + escape(directory) + "</code>";
            }
            case "diff" ->
                text(locale, "approval.diff", "<code>" + escape(string(preview, "path")) + "</code>") + "\n"
                        + TelegramFormat.code(string(preview, "unifiedDiff"), PREVIEW_LIMIT);
            case "fetch" -> text(locale, "approval.fetch", escape(string(preview, "url")));
            case "tool" ->
                text(
                                locale,
                                "approval.tool",
                                "<code>" + escape(string(preview, "server") + "/" + string(preview, "tool"))
                                        + "</code>")
                        + "\n" + TelegramFormat.code(json.writeValueAsString(preview.get("arguments")), PREVIEW_LIMIT);
            case "custom_tool" ->
                text(locale, "approval.custom_tool", "<code>" + escape(string(preview, "name")) + "</code>") + "\n"
                        + TelegramFormat.code(json.writeValueAsString(preview.get("arguments")), PREVIEW_LIMIT);
            case "tool_proposal" ->
                text(locale, "approval.tool_proposal", "<code>" + escape(string(preview, "name")) + "</code>")
                        + "\n" + escape(string(preview, "description")) + "\n"
                        + TelegramFormat.code(string(preview, "body"), PREVIEW_LIMIT);
            default -> TelegramFormat.code(json.writeValueAsString(preview), PREVIEW_LIMIT);
        };
    }

    private static String renderPlan(String title, String locale, Map<String, Object> item) {
        var html = new StringBuilder("<b>").append(escape(title)).append("</b>\n");
        html.append(text(locale, "plan.title")).append("\n\n");
        html.append(escape(String.valueOf(item.getOrDefault("summary", ""))));
        if (item.get("steps") instanceof List<?> steps) {
            var number = 1;
            for (var step : steps) {
                var stepTitle = map(step).get("title");
                html.append('\n').append(number++).append(". ").append(escape(String.valueOf(stepTitle)));
            }
        }
        return html.toString();
    }

    private static TelegramTypes.Keyboard openSessionKeyboard(String locale, UUID sessionId) {
        return TelegramTypes.Keyboard.row(new TelegramTypes.Button(
                text(locale, "button.open"), CallbackData.encode(CallbackData.SELECT_SESSION, sessionId)));
    }

    private static String errorMessage(Object error) {
        var message = map(error).get("message");
        return message == null ? "" : String.valueOf(message);
    }

    private static String string(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> item(Map<String, Object> event) {
        return map(event.get("item"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) {
            return id;
        }
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }

    record Prompt(UUID sessionId, long chatId, long messageId, String html, String locale) {}

    private static final class TurnProgress {

        private final UUID userId;
        private final UUID sessionId;
        private final long chatId;
        private final String locale;
        private final String title;
        private final Map<UUID, String> lines = new LinkedHashMap<>();
        private boolean live;
        private boolean dirty;
        private Long messageId;
        private Instant lastEdit = Instant.EPOCH;
        private String lastAnswer;

        private TurnProgress(UUID userId, UUID sessionId, long chatId, String locale, String title) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.chatId = chatId;
            this.locale = locale;
            this.title = title;
        }
    }
}
