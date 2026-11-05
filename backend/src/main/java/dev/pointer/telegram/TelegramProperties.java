package dev.pointer.telegram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pointer.telegram")
record TelegramProperties(String botToken, String apiUrl, Duration pollTimeout, Duration linkCodeTtl) {

    boolean enabled() {
        return botToken != null && !botToken.isBlank();
    }
}
