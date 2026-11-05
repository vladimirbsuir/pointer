package dev.pointer.telegram;

import dev.pointer.common.error.ApiException;
import dev.pointer.session.SessionDtos;
import dev.pointer.session.SessionService;
import dev.pointer.user.Locales;
import dev.pointer.user.UserService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class TelegramDirectory {

    private final UserService users;
    private final SessionService sessions;

    String locale(UUID userId) {
        try {
            return Locales.supportedOrDefault(users.getActive(userId).getLocale());
        } catch (ApiException exception) {
            return Locales.DEFAULT;
        }
    }

    String accountName(UUID userId) {
        var user = users.getActive(userId);
        return user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? user.getEmail()
                : user.getDisplayName() + " (" + user.getEmail() + ")";
    }

    Optional<SessionDtos.SessionResponse> session(UUID userId, UUID sessionId) {
        try {
            return Optional.of(sessions.get(userId, sessionId));
        } catch (ApiException exception) {
            return Optional.empty();
        }
    }

    String title(String locale, SessionDtos.SessionResponse session) {
        return session.title() == null || session.title().isBlank()
                ? TelegramTexts.text(locale, "untitled")
                : session.title();
    }

    String title(UUID userId, String locale, UUID sessionId) {
        return session(userId, sessionId)
                .map(session -> title(locale, session))
                .orElseGet(() -> TelegramTexts.text(locale, "untitled"));
    }
}
