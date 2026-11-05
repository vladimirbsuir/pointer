package dev.pointer.telegram;

import static dev.pointer.telegram.TelegramTexts.text;

import dev.pointer.common.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telegram")
@RequiredArgsConstructor
@EnableConfigurationProperties(TelegramProperties.class)
class TelegramController {

    private final TelegramLinkService links;
    private final TelegramDispatcher dispatcher;
    private final TelegramDirectory directory;
    private final TelegramApi api;

    @GetMapping
    TelegramDtos.TelegramStatus status(AuthPrincipal principal) {
        return links.status(principal.userId());
    }

    @PostMapping("/link")
    TelegramDtos.LinkCodeResponse createLink(AuthPrincipal principal) {
        return links.createCode(principal.userId());
    }

    @DeleteMapping("/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(AuthPrincipal principal) {
        var userId = principal.userId();
        links.unlink(userId)
                .ifPresent(chatId -> dispatcher.submit(
                        () -> api.sendMessage(chatId, text(directory.locale(userId), "unlinked"), null)));
    }
}
