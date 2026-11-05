package dev.pointer.telegram;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class TelegramBotIdentity {

    private volatile String username;

    Optional<String> username() {
        return Optional.ofNullable(username);
    }

    void update(String username) {
        this.username = username;
    }
}
