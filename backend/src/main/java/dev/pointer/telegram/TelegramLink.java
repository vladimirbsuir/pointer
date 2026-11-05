package dev.pointer.telegram;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "telegram_links")
@Getter
class TelegramLink extends BaseEntity {

    private UUID userId;
    private long chatId;
    private String telegramUsername;
    private UUID selectedSessionId;
    private Instant linkedAt;

    protected TelegramLink() {}

    TelegramLink(UUID userId, long chatId, String telegramUsername, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.chatId = chatId;
        this.telegramUsername = telegramUsername;
        this.linkedAt = now;
    }

    void select(UUID sessionId) {
        this.selectedSessionId = sessionId;
    }
}
