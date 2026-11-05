package dev.pointer.telegram;

import java.util.List;
import java.util.Map;

final class TelegramTexts {

    static final String RU = "ru";

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry(
                    "linked",
                    "Linked to the Pointer account {0}. Pick a session with /sessions or start one with /new."),
            Map.entry(
                    "linkInvalid",
                    "The link is invalid or expired. Create a new one in Pointer: Settings → Account → Telegram."),
            Map.entry(
                    "notLinked",
                    "This chat is not linked to Pointer. Open Pointer, go to Settings → Account → Telegram and click Link Telegram."),
            Map.entry("privateOnly", "Pointer works only in a private chat with the bot."),
            Map.entry(
                    "help",
                    "<b>Pointer remote control</b>\n\n"
                            + "/sessions: choose a session\n"
                            + "/new: start a session in a project\n"
                            + "/stop: stop the running turn\n"
                            + "/unlink: unlink this chat\n\n"
                            + "Other messages go to the selected session. Reply to an approval request with text to deny it with that message."),
            Map.entry("unlinked", "This chat is unlinked from Pointer."),
            Map.entry("noSessions", "No sessions yet. Start one with /new."),
            Map.entry(
                    "sessionsTitle",
                    "Recent sessions. The selected one receives your messages and shows live progress."),
            Map.entry("untitled", "New session"),
            Map.entry("noProjects", "No projects yet. Add one in the Pointer desktop app."),
            Map.entry("projectsTitle", "Choose a project for the new session:"),
            Map.entry(
                    "noDevice",
                    "No online computer has {0} open. Start Pointer on your computer and open the project, then try again."),
            Map.entry("sessionCreated", "New session in {0}. Send your task."),
            Map.entry(
                    "sessionCreatedOnServer",
                    "No computer with {0} is online, so the session runs on the server in branch {1}. Send your task."),
            Map.entry("sessionSelected", "Selected <b>{0}</b> · {1} · {2}"),
            Map.entry("lastAnswer", "Last answer:"),
            Map.entry("noSessionSelected", "Choose a session with /sessions or start one with /new."),
            Map.entry("queued", "Queued: the message runs after the current turn."),
            Map.entry("nothingToStop", "Nothing is running in the selected session."),
            Map.entry("stopping", "Stopping the running turn."),
            Map.entry("textOnly", "Only text messages are supported."),
            Map.entry("failed", "Could not do that: {0}"),
            Map.entry("unknownCommand", "Unknown command. See /help."),
            Map.entry("status.idle", "idle"),
            Map.entry("status.running", "running"),
            Map.entry("status.waiting_for_approval", "waiting for approval"),
            Map.entry("status.waiting_for_executor", "waiting for the computer"),
            Map.entry("status.error", "error"),
            Map.entry("progress.working", "⏳ Working…"),
            Map.entry("progress.completed", "✅ Done"),
            Map.entry("progress.cancelled", "⏹ Cancelled"),
            Map.entry("progress.failed", "❌ Failed"),
            Map.entry("progress.step_limit", "⚠️ Stopped at the step limit"),
            Map.entry("progress.earlier", "… {0} earlier steps"),
            Map.entry("approval.title", "🔐 Approval needed"),
            Map.entry("approval.command", "Run a command"),
            Map.entry("approval.diff", "Change {0}"),
            Map.entry("approval.fetch", "Fetch {0}"),
            Map.entry("approval.tool", "Call {0}"),
            Map.entry("approval.custom_tool", "Run the custom tool {0}"),
            Map.entry("approval.tool_proposal", "Save the new tool {0}"),
            Map.entry("approval.approved", "✅ Approved"),
            Map.entry("approval.approvedSession", "✅ Approved for the session"),
            Map.entry("approval.denied", "🚫 Denied"),
            Map.entry("approval.cancelled", "⏹ Cancelled"),
            Map.entry("approval.gone", "This request is no longer pending."),
            Map.entry("button.approve", "Approve"),
            Map.entry("button.approveSession", "Allow for session"),
            Map.entry("button.deny", "Deny"),
            Map.entry("button.approvePlan", "Approve plan"),
            Map.entry("button.open", "Open session"),
            Map.entry("plan.title", "📋 Plan ready for review"),
            Map.entry("plan.approved", "✅ Plan approved"),
            Map.entry("plan.rejected", "Plan replaced by a new message"),
            Map.entry("turnFailed", "❌ Turn failed: {0}"),
            Map.entry("selected", "Selected"),
            Map.entry("command.sessions", "Choose a session"),
            Map.entry("command.new", "Start a session in a project"),
            Map.entry("command.stop", "Stop the running turn"),
            Map.entry("command.help", "How to use the bot"),
            Map.entry("command.unlink", "Unlink this chat"));

    private static final Map<String, String> RU_TEXTS = Map.ofEntries(
            Map.entry(
                    "linked",
                    "Аккаунт Pointer {0} привязан. Выберите сессию через /sessions или начните новую через /new."),
            Map.entry(
                    "linkInvalid",
                    "Ссылка недействительна или устарела. Создайте новую в Pointer: Настройки → Аккаунт → Telegram."),
            Map.entry(
                    "notLinked",
                    "Этот чат не привязан к Pointer. Откройте Pointer, перейдите в Настройки → Аккаунт → Telegram и нажмите «Привязать Telegram»."),
            Map.entry("privateOnly", "Pointer работает только в личном чате с ботом."),
            Map.entry(
                    "help",
                    "<b>Удалённое управление Pointer</b>\n\n"
                            + "/sessions: выбрать сессию\n"
                            + "/new: начать сессию в проекте\n"
                            + "/stop: остановить текущий ход\n"
                            + "/unlink: отвязать этот чат\n\n"
                            + "Остальные сообщения уходят в выбранную сессию. Ответьте текстом на запрос подтверждения, чтобы отклонить его с этим сообщением."),
            Map.entry("unlinked", "Чат отвязан от Pointer."),
            Map.entry("noSessions", "Сессий пока нет. Начните новую через /new."),
            Map.entry("sessionsTitle", "Последние сессии. Выбранная получает ваши сообщения и показывает ход работы."),
            Map.entry("untitled", "Новая сессия"),
            Map.entry("noProjects", "Проектов пока нет. Добавьте проект в приложении Pointer."),
            Map.entry("projectsTitle", "Выберите проект для новой сессии:"),
            Map.entry(
                    "noDevice",
                    "Ни на одном включённом компьютере не открыт {0}. Запустите Pointer, откройте проект и попробуйте снова."),
            Map.entry("sessionCreated", "Новая сессия в {0}. Отправьте задачу."),
            Map.entry(
                    "sessionCreatedOnServer",
                    "Ни один компьютер с {0} не в сети, поэтому сессия работает на сервере в ветке {1}. Отправьте задачу."),
            Map.entry("sessionSelected", "Выбрана <b>{0}</b> · {1} · {2}"),
            Map.entry("lastAnswer", "Последний ответ:"),
            Map.entry("noSessionSelected", "Выберите сессию через /sessions или начните новую через /new."),
            Map.entry("queued", "В очереди: сообщение выполнится после текущего хода."),
            Map.entry("nothingToStop", "В выбранной сессии ничего не выполняется."),
            Map.entry("stopping", "Останавливаю текущий ход."),
            Map.entry("textOnly", "Поддерживаются только текстовые сообщения."),
            Map.entry("failed", "Не получилось: {0}"),
            Map.entry("unknownCommand", "Неизвестная команда. Смотрите /help."),
            Map.entry("status.idle", "ожидает"),
            Map.entry("status.running", "работает"),
            Map.entry("status.waiting_for_approval", "ждёт подтверждения"),
            Map.entry("status.waiting_for_executor", "ждёт компьютер"),
            Map.entry("status.error", "ошибка"),
            Map.entry("progress.working", "⏳ Работаю…"),
            Map.entry("progress.completed", "✅ Готово"),
            Map.entry("progress.cancelled", "⏹ Остановлено"),
            Map.entry("progress.failed", "❌ Ошибка"),
            Map.entry("progress.step_limit", "⚠️ Достигнут лимит шагов"),
            Map.entry("progress.earlier", "… ещё шагов ранее: {0}"),
            Map.entry("approval.title", "🔐 Нужно подтверждение"),
            Map.entry("approval.command", "Выполнить команду"),
            Map.entry("approval.diff", "Изменить {0}"),
            Map.entry("approval.fetch", "Загрузить {0}"),
            Map.entry("approval.tool", "Вызвать {0}"),
            Map.entry("approval.custom_tool", "Запустить пользовательский инструмент {0}"),
            Map.entry("approval.tool_proposal", "Сохранить новый инструмент {0}"),
            Map.entry("approval.approved", "✅ Разрешено"),
            Map.entry("approval.approvedSession", "✅ Разрешено для сессии"),
            Map.entry("approval.denied", "🚫 Отклонено"),
            Map.entry("approval.cancelled", "⏹ Отменено"),
            Map.entry("approval.gone", "Этот запрос уже неактуален."),
            Map.entry("button.approve", "Разрешить"),
            Map.entry("button.approveSession", "Разрешить в сессии"),
            Map.entry("button.deny", "Отклонить"),
            Map.entry("button.approvePlan", "Утвердить план"),
            Map.entry("button.open", "Открыть сессию"),
            Map.entry("plan.title", "📋 План готов к проверке"),
            Map.entry("plan.approved", "✅ План утверждён"),
            Map.entry("plan.rejected", "План заменён новым сообщением"),
            Map.entry("turnFailed", "❌ Ход завершился ошибкой: {0}"),
            Map.entry("selected", "Выбрана"),
            Map.entry("command.sessions", "Выбрать сессию"),
            Map.entry("command.new", "Начать сессию в проекте"),
            Map.entry("command.stop", "Остановить текущий ход"),
            Map.entry("command.help", "Как пользоваться ботом"),
            Map.entry("command.unlink", "Отвязать этот чат"));

    static final List<String> COMMANDS = List.of("sessions", "new", "stop", "help", "unlink");

    private TelegramTexts() {}

    static String text(String locale, String key, Object... arguments) {
        var template = (RU.equals(locale) ? RU_TEXTS : EN).getOrDefault(key, EN.getOrDefault(key, key));
        for (var index = 0; index < arguments.length; index++) {
            template = template.replace("{" + index + "}", String.valueOf(arguments[index]));
        }
        return template;
    }

    static boolean has(String key) {
        return EN.containsKey(key);
    }
}
