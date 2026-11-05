package dev.pointer.telegram;

import static dev.pointer.telegram.TelegramFormat.escape;
import static dev.pointer.telegram.TelegramTexts.text;

import dev.pointer.agent.AgentRunner;
import dev.pointer.common.error.ApiException;
import dev.pointer.execution.ExecutionService;
import dev.pointer.project.ProjectService;
import dev.pointer.session.ApprovalService;
import dev.pointer.session.SessionDtos;
import dev.pointer.session.SessionService;
import dev.pointer.user.Locales;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class TelegramBot {

    private static final int SESSION_LIST_LIMIT = 10;
    private static final int BUTTON_TITLE_LIMIT = 40;
    private static final int LAST_ANSWER_LIMIT = 1500;
    private static final String START = "/start";

    private final TelegramApi api;
    private final TelegramLinkService links;
    private final TelegramNotifier notifier;
    private final TelegramDirectory directory;
    private final SessionService sessions;
    private final ProjectService projects;
    private final ApprovalService approvals;
    private final ExecutionService execution;
    private final AgentRunner runner;

    void handle(TelegramTypes.Update update) {
        if (update.message() != null) {
            handleMessage(update.updateId(), update.message());
        } else if (update.callbackQuery() != null) {
            handleCallback(update.callbackQuery());
        }
    }

    private void handleMessage(long updateId, TelegramTypes.Message message) {
        var chatId = message.chat().id();
        if (!message.chat().isPrivate()) {
            api.sendMessage(chatId, text(senderLocale(message.from()), "privateOnly"), null);
            return;
        }
        var content = message.text() == null ? "" : message.text().strip();
        if (content.equals(START) || content.startsWith(START + " ")) {
            start(message, content.substring(START.length()).strip());
            return;
        }
        var link = links.forChat(chatId);
        if (link.isEmpty()) {
            api.sendMessage(chatId, text(senderLocale(message.from()), "notLinked"), null);
            return;
        }
        var chat = new Chat(link.get(), directory.locale(link.get().userId()));
        if (content.isEmpty()) {
            chat.reply(text(chat.locale, "textOnly"));
            return;
        }
        if (content.startsWith("/")) {
            command(chat, commandName(content));
            return;
        }
        var replyTo = message.replyTo();
        var approval = replyTo == null
                ? Optional.<Map.Entry<UUID, TelegramNotifier.Prompt>>empty()
                : notifier.approvalByMessage(chatId, replyTo.messageId());
        if (approval.isPresent()) {
            resolveApproval(
                    chat, approval.get().getKey(), approval.get().getValue().sessionId(), "denied", null, content);
            return;
        }
        sendToSession(chat, updateId, content);
    }

    private void start(TelegramTypes.Message message, String code) {
        var chatId = message.chat().id();
        if (code.isEmpty()) {
            var link = links.forChat(chatId);
            if (link.isEmpty()) {
                api.sendMessage(chatId, text(senderLocale(message.from()), "notLinked"), null);
            } else {
                api.sendMessage(chatId, text(directory.locale(link.get().userId()), "help"), null);
            }
            return;
        }
        var username = message.from() == null ? null : message.from().username();
        var userId = links.redeem(code, chatId, username);
        if (userId.isEmpty()) {
            api.sendMessage(chatId, text(senderLocale(message.from()), "linkInvalid"), null);
            return;
        }
        var locale = directory.locale(userId.get());
        var account = directory.accountName(userId.get());
        api.sendMessage(chatId, text(locale, "linked", "<b>" + escape(account) + "</b>"), null);
    }

    private void command(Chat chat, String name) {
        switch (name) {
            case "sessions" -> listSessions(chat);
            case "new" -> listProjects(chat);
            case "stop" -> stop(chat);
            case "unlink" -> {
                links.unlink(chat.link.userId());
                chat.reply(text(chat.locale, "unlinked"));
            }
            case "help" -> chat.reply(text(chat.locale, "help"));
            default -> chat.reply(text(chat.locale, "unknownCommand"));
        }
    }

    private void listSessions(Chat chat) {
        var recent = sessions.recent(chat.link.userId(), SESSION_LIST_LIMIT);
        if (recent.isEmpty()) {
            chat.reply(text(chat.locale, "noSessions"));
            return;
        }
        var projectNames = projectNames(chat.link.userId());
        var buttons = recent.stream()
                .map(session -> new TelegramTypes.Button(
                        sessionLabel(chat, session, projectNames.get(session.projectId())),
                        CallbackData.encode(CallbackData.SELECT_SESSION, session.id())))
                .toList();
        chat.reply(text(chat.locale, "sessionsTitle"), TelegramTypes.Keyboard.column(buttons));
    }

    private String sessionLabel(Chat chat, SessionDtos.SessionResponse session, String projectName) {
        var marker = chat.link.isSelected(session.id()) ? "▶ " : "";
        var title = TelegramFormat.shorten(directory.title(chat.locale, session), BUTTON_TITLE_LIMIT);
        var status = statusIcon(session.status());
        return marker + status + title + (projectName == null ? "" : " · " + projectName);
    }

    private static String statusIcon(String status) {
        return switch (status) {
            case "running", "waiting_for_executor" -> "⏳ ";
            case "waiting_for_approval" -> "🔐 ";
            case "error" -> "❌ ";
            default -> "";
        };
    }

    private void listProjects(Chat chat) {
        var active = projects.list(chat.link.userId()).stream()
                .filter(project -> project.archivedAt() == null)
                .toList();
        if (active.isEmpty()) {
            chat.reply(text(chat.locale, "noProjects"));
            return;
        }
        var buttons = active.stream()
                .map(project -> new TelegramTypes.Button(
                        TelegramFormat.shorten(project.name(), BUTTON_TITLE_LIMIT),
                        CallbackData.encode(CallbackData.NEW_SESSION, project.id())))
                .toList();
        chat.reply(text(chat.locale, "projectsTitle"), TelegramTypes.Keyboard.column(buttons));
    }

    private void stop(Chat chat) {
        var sessionId = chat.link.selectedSessionId();
        var session = sessionId == null
                ? Optional.<SessionDtos.SessionResponse>empty()
                : directory.session(chat.link.userId(), sessionId);
        if (session.isEmpty()
                || "idle".equals(session.get().status())
                || "error".equals(session.get().status())) {
            chat.reply(text(chat.locale, "nothingToStop"));
            return;
        }
        runner.cancel(chat.link.userId(), sessionId);
        chat.reply(text(chat.locale, "stopping"));
    }

    private void sendToSession(Chat chat, long updateId, String content) {
        var sessionId = chat.link.selectedSessionId();
        if (sessionId == null
                || directory.session(chat.link.userId(), sessionId).isEmpty()) {
            chat.reply(text(chat.locale, "noSessionSelected"));
            return;
        }
        try {
            var response = runner.send(
                    chat.link.userId(),
                    sessionId,
                    new SessionDtos.SendMessageRequest(content, null, null, null),
                    "telegram-" + updateId);
            if (response.queued()) {
                chat.reply(text(chat.locale, "queued"));
            }
        } catch (ApiException exception) {
            chat.reply(text(chat.locale, "failed", escape(exception.getMessage())));
        }
    }

    private void handleCallback(TelegramTypes.CallbackQuery callback) {
        var message = callback.message();
        var data = CallbackData.decode(callback.data());
        var link = message == null
                ? Optional.<TelegramLinkService.LinkView>empty()
                : links.forChat(message.chat().id());
        if (link.isEmpty() || data.isEmpty()) {
            api.answerCallback(callback.id(), text(senderLocale(callback.from()), "notLinked"));
            return;
        }
        var chat = new Chat(link.get(), directory.locale(link.get().userId()));
        var callbackData = data.get();
        try {
            var answer = switch (callbackData.action()) {
                case CallbackData.SELECT_SESSION -> selectSession(chat, callbackData.id());
                case CallbackData.NEW_SESSION -> newSession(chat, callbackData.id());
                case CallbackData.APPROVAL -> approvalButton(chat, callbackData);
                case CallbackData.PLAN -> approvePlan(chat, callbackData.id());
                default -> null;
            };
            api.answerCallback(callback.id(), answer);
        } catch (ApiException exception) {
            api.answerCallback(callback.id(), TelegramFormat.shorten(exception.getMessage(), 190));
        }
    }

    private String selectSession(Chat chat, UUID sessionId) {
        var session = sessions.get(chat.link.userId(), sessionId);
        links.select(chat.link.userId(), sessionId);
        var projectName = projectNames(chat.link.userId()).get(session.projectId());
        var html = new StringBuilder(text(
                chat.locale,
                "sessionSelected",
                escape(directory.title(chat.locale, session)),
                escape(projectName == null ? "" : projectName),
                text(chat.locale, "status." + session.status())));
        lastAnswer(chat.link.userId(), sessionId)
                .ifPresent(answer -> html.append("\n\n")
                        .append(text(chat.locale, "lastAnswer"))
                        .append('\n')
                        .append(TelegramFormat.markdownToHtml(TelegramFormat.shortenBlock(answer, LAST_ANSWER_LIMIT))));
        chat.reply(html.toString());
        notifier.sessionSelected(chat.link.userId(), sessionId);
        return text(chat.locale, "selected");
    }

    private Optional<String> lastAnswer(UUID userId, UUID sessionId) {
        var items = sessions.items(userId, sessionId);
        for (var index = items.size() - 1; index >= 0; index--) {
            var item = items.get(index);
            if ("assistant_message".equals(item.get("type"))
                    && item.get("text") instanceof String answer
                    && !answer.isBlank()) {
                return Optional.of(answer);
            }
        }
        return Optional.empty();
    }

    private String newSession(Chat chat, UUID projectId) {
        var project = projects.owned(chat.link.userId(), projectId);
        var deviceId = execution.executorFor(chat.link.userId(), projectId);
        if (deviceId.isEmpty()) {
            chat.reply(text(chat.locale, "noDevice", "<b>" + escape(project.getName()) + "</b>"));
            return null;
        }
        var session = sessions.create(
                chat.link.userId(), deviceId.get(), projectId, new SessionDtos.CreateSessionRequest(null, null, null));
        links.select(chat.link.userId(), session.id());
        chat.reply(text(chat.locale, "sessionCreated", "<b>" + escape(project.getName()) + "</b>"));
        return null;
    }

    private String approvalButton(Chat chat, CallbackData data) {
        var prompt = notifier.approval(data.id());
        if (prompt.isEmpty()) {
            return text(chat.locale, "approval.gone");
        }
        var sessionId = prompt.get().sessionId();
        return switch (data.option()) {
            case CallbackData.APPROVE_ONCE -> resolveApproval(chat, data.id(), sessionId, "approved", "once", null);
            case CallbackData.APPROVE_SESSION ->
                resolveApproval(chat, data.id(), sessionId, "approved", "session", null);
            default -> resolveApproval(chat, data.id(), sessionId, "denied", null, null);
        };
    }

    private String resolveApproval(
            Chat chat, UUID approvalId, UUID sessionId, String decision, String scope, String message) {
        try {
            approvals.resolve(
                    chat.link.userId(),
                    sessionId,
                    approvalId,
                    new ApprovalService.ResolveApprovalRequest(decision, scope, message, null));
            return null;
        } catch (ApiException exception) {
            return text(chat.locale, "approval.gone");
        }
    }

    private String approvePlan(Chat chat, UUID itemId) {
        var prompt = notifier.plan(itemId);
        if (prompt.isEmpty()) {
            return text(chat.locale, "approval.gone");
        }
        runner.approvePlan(chat.link.userId(), prompt.get().sessionId(), itemId);
        return null;
    }

    private Map<UUID, String> projectNames(UUID userId) {
        return projects.list(userId).stream()
                .collect(Collectors.toMap(
                        ProjectService.ProjectResponse::id,
                        ProjectService.ProjectResponse::name,
                        (first, second) -> first));
    }

    private static String commandName(String content) {
        var name = content.substring(1).split("[\\s@]", 2)[0];
        return name.toLowerCase(Locale.ROOT);
    }

    private static String senderLocale(TelegramTypes.Sender sender) {
        var language = sender == null ? null : sender.languageCode();
        return language != null && language.startsWith(TelegramTexts.RU) ? TelegramTexts.RU : Locales.DEFAULT;
    }

    private final class Chat {

        private final TelegramLinkService.LinkView link;
        private final String locale;

        private Chat(TelegramLinkService.LinkView link, String locale) {
            this.link = link;
            this.locale = locale;
        }

        void reply(String html) {
            reply(html, null);
        }

        void reply(String html, TelegramTypes.Keyboard keyboard) {
            api.sendMessage(link.chatId(), html, keyboard);
        }
    }
}
