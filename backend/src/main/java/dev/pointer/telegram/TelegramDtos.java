package dev.pointer.telegram;

import java.time.Instant;

final class TelegramDtos {

    private TelegramDtos() {}

    record TelegramStatus(
            boolean enabled, String botUsername, boolean linked, String telegramUsername, Instant linkedAt) {}

    record LinkCodeResponse(String url, Instant expiresAt) {}
}
