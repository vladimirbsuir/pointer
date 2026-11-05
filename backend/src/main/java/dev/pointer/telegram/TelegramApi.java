package dev.pointer.telegram;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class TelegramApi {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_MARGIN = Duration.ofSeconds(15);
    private static final int MAX_RETRY_AFTER_SECONDS = 30;
    private static final String HTML = "HTML";

    private final TelegramProperties properties;
    private final JsonMapper json;
    private final RestClient rest;

    TelegramApi(TelegramProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(properties.pollTimeout().plus(READ_MARGIN));
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    TelegramTypes.BotProfile getMe() {
        return json.treeToValue(call("getMe", Map.of()), TelegramTypes.BotProfile.class);
    }

    List<TelegramTypes.Update> getUpdates(long offset) {
        var body = new LinkedHashMap<String, Object>();
        body.put("offset", offset);
        body.put("timeout", properties.pollTimeout().toSeconds());
        body.put("allowed_updates", List.of("message", "callback_query"));
        var updates = new ArrayList<TelegramTypes.Update>();
        for (var node : call("getUpdates", body)) {
            updates.add(json.treeToValue(node, TelegramTypes.Update.class));
        }
        return updates;
    }

    long sendMessage(long chatId, String html, TelegramTypes.Keyboard keyboard) {
        var body = new LinkedHashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", HTML);
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (keyboard != null && !keyboard.rows().isEmpty()) {
            body.put("reply_markup", keyboard.toJson());
        }
        try {
            return call("sendMessage", body).path("message_id").asLong();
        } catch (TelegramException exception) {
            if (!exception.isParseError()) {
                throw exception;
            }
            body.remove("parse_mode");
            body.put("text", TelegramFormat.plain(html));
            return call("sendMessage", body).path("message_id").asLong();
        }
    }

    void editMessage(long chatId, long messageId, String html, TelegramTypes.Keyboard keyboard) {
        var body = new LinkedHashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", html);
        body.put("parse_mode", HTML);
        body.put("link_preview_options", Map.of("is_disabled", true));
        body.put("reply_markup", (keyboard == null ? TelegramTypes.Keyboard.NONE : keyboard).toJson());
        try {
            call("editMessageText", body);
        } catch (TelegramException exception) {
            if (exception.isNotModified()) {
                return;
            }
            if (!exception.isParseError()) {
                throw exception;
            }
            body.remove("parse_mode");
            body.put("text", TelegramFormat.plain(html));
            call("editMessageText", body);
        }
    }

    void answerCallback(String callbackId, String text) {
        var body = new LinkedHashMap<String, Object>();
        body.put("callback_query_id", callbackId);
        if (text != null) {
            body.put("text", text);
        }
        call("answerCallbackQuery", body);
    }

    void setCommands(List<Map<String, String>> commands, String languageCode) {
        var body = new LinkedHashMap<String, Object>();
        body.put("commands", commands);
        if (languageCode != null) {
            body.put("language_code", languageCode);
        }
        call("setMyCommands", body);
    }

    private JsonNode call(String method, Map<String, Object> body) {
        var response = post(method, body);
        if (!response.path("ok").asBoolean()) {
            var retryAfter = response.path("parameters").path("retry_after").asInt(0);
            if (response.path("error_code").asInt() == 429 && retryAfter > 0 && retryAfter <= MAX_RETRY_AFTER_SECONDS) {
                sleep(Duration.ofSeconds(retryAfter));
                response = post(method, body);
            }
        }
        if (!response.path("ok").asBoolean()) {
            throw new TelegramException(
                    response.path("error_code").asInt(),
                    method + ": " + response.path("description").asString(""));
        }
        return response.path("result");
    }

    private JsonNode post(String method, Map<String, Object> body) {
        try {
            return rest.post()
                    .uri(properties.apiUrl() + "/bot" + properties.botToken() + "/" + method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .exchange((request, response) -> json.readTree(response.getBody()), true);
        } catch (RestClientException | JacksonException exception) {
            throw new TelegramException(
                    0, method + ": request failed (" + exception.getClass().getSimpleName() + ")");
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
