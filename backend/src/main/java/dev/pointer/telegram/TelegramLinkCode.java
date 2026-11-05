package dev.pointer.telegram;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "telegram_link_codes")
@Getter
class TelegramLinkCode extends BaseEntity {

    private UUID userId;
    private String codeHash;
    private Instant expiresAt;

    protected TelegramLinkCode() {}

    TelegramLinkCode(UUID userId, String codeHash, Instant expiresAt) {
        super(Ids.newId());
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
