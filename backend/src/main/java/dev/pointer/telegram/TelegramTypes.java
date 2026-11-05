package dev.pointer.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

final class TelegramTypes {

    private TelegramTypes() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Update(
            @JsonProperty("update_id") long updateId,
            Message message,
            @JsonProperty("callback_query") CallbackQuery callbackQuery) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(
            @JsonProperty("message_id") long messageId,
            Chat chat,
            Sender from,
            String text,
            @JsonProperty("reply_to_message") Message replyTo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chat(long id, String type) {

        boolean isPrivate() {
            return "private".equals(type);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Sender(
            long id,
            String username,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("language_code") String languageCode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CallbackQuery(String id, Sender from, Message message, String data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BotProfile(long id, String username) {}

    record Button(String text, String callbackData) {

        Map<String, Object> toJson() {
            return Map.of("text", text, "callback_data", callbackData);
        }
    }

    record Keyboard(List<List<Button>> rows) {

        static final Keyboard NONE = new Keyboard(List.of());

        static Keyboard column(List<Button> buttons) {
            return new Keyboard(buttons.stream().map(List::of).toList());
        }

        static Keyboard row(Button... buttons) {
            return new Keyboard(List.of(List.of(buttons)));
        }

        Map<String, Object> toJson() {
            return Map.of(
                    "inline_keyboard",
                    rows.stream()
                            .map(row -> row.stream().map(Button::toJson).toList())
                            .toList());
        }
    }
}
