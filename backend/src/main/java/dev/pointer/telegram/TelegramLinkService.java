package dev.pointer.telegram;

import dev.pointer.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class TelegramLinkService {

    private static final int CODE_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TelegramLinkRepository links;
    private final TelegramLinkCodeRepository codes;
    private final TelegramProperties properties;
    private final TelegramBotIdentity identity;
    private final Clock clock;
    private final Map<UUID, Optional<LinkView>> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public TelegramDtos.TelegramStatus status(UUID userId) {
        var link = links.findByUserId(userId);
        return new TelegramDtos.TelegramStatus(
                properties.enabled(),
                identity.username().orElse(null),
                link.isPresent(),
                link.map(TelegramLink::getTelegramUsername).orElse(null),
                link.map(TelegramLink::getLinkedAt).orElse(null));
    }

    @Transactional
    public TelegramDtos.LinkCodeResponse createCode(UUID userId) {
        if (!properties.enabled()) {
            throw ApiException.unavailable("telegram_disabled", "The Telegram bot is not configured on the server");
        }
        var username = identity.username()
                .orElseThrow(
                        () -> ApiException.unavailable("telegram_unavailable", "The Telegram bot is not ready yet"));
        codes.deleteByUser(userId);
        codes.flush();
        var bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        var code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var expiresAt = clock.instant().plus(properties.linkCodeTtl());
        codes.save(new TelegramLinkCode(userId, hash(code), expiresAt));
        return new TelegramDtos.LinkCodeResponse("https://t.me/" + username + "?start=" + code, expiresAt);
    }

    @Transactional
    public Optional<UUID> redeem(String code, long chatId, String telegramUsername) {
        var found = codes.findByCodeHash(hash(code));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var linkCode = found.get();
        codes.delete(linkCode);
        if (linkCode.isExpired(clock.instant())) {
            return Optional.empty();
        }
        var userId = linkCode.getUserId();
        links.findByChatId(chatId).ifPresent(previous -> cache.remove(previous.getUserId()));
        links.deleteByUserIdOrChatId(userId, chatId);
        links.flush();
        links.save(new TelegramLink(userId, chatId, telegramUsername, clock.instant()));
        cache.remove(userId);
        return Optional.of(userId);
    }

    @Transactional
    public Optional<Long> unlink(UUID userId) {
        var chatId = links.findByUserId(userId).map(TelegramLink::getChatId);
        links.deleteByUser(userId);
        cache.remove(userId);
        return chatId;
    }

    @Transactional
    public void select(UUID userId, UUID sessionId) {
        links.findByUserId(userId).ifPresent(link -> {
            link.select(sessionId);
            links.save(link);
        });
        cache.remove(userId);
    }

    @Transactional(readOnly = true)
    public Optional<LinkView> forUser(UUID userId) {
        var cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        var view = links.findByUserId(userId).map(LinkView::of);
        cache.put(userId, view);
        return view;
    }

    @Transactional(readOnly = true)
    public Optional<LinkView> forChat(long chatId) {
        return links.findByChatId(chatId).map(LinkView::of);
    }

    private static String hash(String code) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record LinkView(UUID userId, long chatId, UUID selectedSessionId) {

        static LinkView of(TelegramLink link) {
            return new LinkView(link.getUserId(), link.getChatId(), link.getSelectedSessionId());
        }

        boolean isSelected(UUID sessionId) {
            return sessionId.equals(selectedSessionId);
        }
    }
}
