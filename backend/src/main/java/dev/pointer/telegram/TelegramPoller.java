package dev.pointer.telegram;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class TelegramPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final TelegramProperties properties;
    private final TelegramApi api;
    private final TelegramBotIdentity identity;
    private final TelegramDispatcher dispatcher;
    private final TelegramBot bot;
    private volatile boolean running;
    private volatile Thread thread;

    TelegramPoller(
            TelegramProperties properties,
            TelegramApi api,
            TelegramBotIdentity identity,
            TelegramDispatcher dispatcher,
            TelegramBot bot) {
        this.properties = properties;
        this.api = api;
        this.identity = identity;
        this.dispatcher = dispatcher;
        this.bot = bot;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!properties.enabled()) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("telegram-poller").start(this::run);
    }

    @PreDestroy
    void stop() {
        running = false;
        var current = thread;
        if (current != null) {
            current.interrupt();
        }
    }

    private void run() {
        while (running && identity.username().isEmpty()) {
            try {
                var profile = api.getMe();
                identity.update(profile.username());
                registerCommands();
                log.info("Telegram bot @{} is polling for updates", profile.username());
            } catch (TelegramException exception) {
                log.warn("Telegram bot is not reachable: {}", exception.getMessage());
                pause();
            }
        }
        var offset = 0L;
        while (running) {
            try {
                for (var update : api.getUpdates(offset)) {
                    offset = Math.max(offset, update.updateId() + 1);
                    dispatcher.submit(() -> bot.handle(update));
                }
            } catch (TelegramException exception) {
                if (running) {
                    log.warn("Telegram polling failed: {}", exception.getMessage());
                    pause();
                }
            }
        }
    }

    private void registerCommands() {
        for (var locale : List.of("en", TelegramTexts.RU)) {
            var commands = TelegramTexts.COMMANDS.stream()
                    .map(command ->
                            Map.of("command", command, "description", TelegramTexts.text(locale, "command." + command)))
                    .toList();
            try {
                api.setCommands(commands, "en".equals(locale) ? null : locale);
            } catch (TelegramException exception) {
                log.warn("Could not register Telegram commands: {}", exception.getMessage());
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(RETRY_DELAY);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
