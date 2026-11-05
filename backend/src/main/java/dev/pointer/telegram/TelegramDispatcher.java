package dev.pointer.telegram;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class TelegramDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TelegramDispatcher.class);

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("telegram-worker").factory());
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("telegram-ticker").factory());

    void submit(Runnable task) {
        if (worker.isShutdown()) {
            return;
        }
        worker.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException exception) {
                log.warn("Telegram task failed: {}", exception.getMessage());
            }
        });
    }

    void every(Duration interval, Runnable task) {
        ticker.scheduleWithFixedDelay(
                () -> submit(task), interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
        worker.shutdownNow();
    }
}
