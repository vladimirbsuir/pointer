package dev.pointer.telegram;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TelegramLinkRepository extends JpaRepository<TelegramLink, UUID> {

    Optional<TelegramLink> findByUserId(UUID userId);

    Optional<TelegramLink> findByChatId(long chatId);

    @Modifying
    @Query("delete from TelegramLink l where l.userId = :userId or l.chatId = :chatId")
    void deleteByUserIdOrChatId(UUID userId, long chatId);

    @Modifying
    @Query("delete from TelegramLink l where l.userId = :userId")
    int deleteByUser(UUID userId);
}

interface TelegramLinkCodeRepository extends JpaRepository<TelegramLinkCode, UUID> {

    Optional<TelegramLinkCode> findByCodeHash(String codeHash);

    @Modifying
    @Query("delete from TelegramLinkCode c where c.userId = :userId")
    void deleteByUser(UUID userId);
}
