package dev.pointer.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class FakeTelegramServer implements AutoCloseable {

    public static final String TOKEN = "123456:test-token";
    public static final String BOT_USERNAME = "pointer_test_bot";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final LinkedBlockingQueue<Map<String, Object>> updates = new LinkedBlockingQueue<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicLong updateIds = new AtomicLong(1);
    private final AtomicLong messageIds = new AtomicLong(1000);

    public FakeTelegramServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/bot" + TOKEN + "/", this::handle);
        server.start();
    }

    public String apiUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void enqueueText(long chatId, String text) {
        enqueueMessage(chatId, text, null);
    }

    public void enqueueReply(long chatId, String text, long replyToMessageId) {
        enqueueMessage(chatId, text, Map.of("message_id", replyToMessageId, "chat", chat(chatId)));
    }

    public void enqueueCallback(long chatId, long messageId, String data) {
        var callback = new LinkedHashMap<String, Object>();
        callback.put("id", "cb" + updateIds.get());
        callback.put("from", sender(chatId));
        callback.put("message", Map.of("message_id", messageId, "chat", chat(chatId)));
        callback.put("data", data);
        updates.add(Map.of("update_id", updateIds.getAndIncrement(), "callback_query", callback));
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public Call await(String method, Predicate<JsonNode> predicate, Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (var call : calls) {
                if (call.method().equals(method) && predicate.test(call.body())) {
                    return call;
                }
            }
            sleep();
        }
        throw new AssertionError("No " + method + " call matched within " + timeout + ". Calls: " + calls);
    }

    public Call awaitMessage(long chatId, String fragment, Duration timeout) {
        return await(
                "sendMessage",
                body -> body.path("chat_id").asLong() == chatId
                        && body.path("text").asString().contains(fragment),
                timeout);
    }

    public Call awaitEdit(long chatId, String fragment, Duration timeout) {
        return await(
                "editMessageText",
                body -> body.path("chat_id").asLong() == chatId
                        && body.path("text").asString().contains(fragment),
                timeout);
    }

    public static String button(Call call, String label) {
        for (var row : call.body().path("reply_markup").path("inline_keyboard")) {
            for (var button : row) {
                if (button.path("text").asString().contains(label)) {
                    return button.path("callback_data").asString();
                }
            }
        }
        throw new AssertionError("No button " + label + " in " + call.body());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void enqueueMessage(long chatId, String text, Map<String, Object> replyTo) {
        var message = new LinkedHashMap<String, Object>();
        message.put("message_id", messageIds.getAndIncrement());
        message.put("chat", chat(chatId));
        message.put("from", sender(chatId));
        message.put("text", text);
        if (replyTo != null) {
            message.put("reply_to_message", replyTo);
        }
        updates.add(Map.of("update_id", updateIds.getAndIncrement(), "message", message));
    }

    private static Map<String, Object> chat(long chatId) {
        return Map.of("id", chatId, "type", "private");
    }

    private static Map<String, Object> sender(long chatId) {
        return Map.of("id", chatId, "username", "tester", "first_name", "Test", "language_code", "en");
    }

    private void handle(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        var method = path.substring(path.lastIndexOf('/') + 1);
        var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var body = raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
        Object result = switch (method) {
            case "getMe" -> Map.of("id", 1, "is_bot", true, "username", BOT_USERNAME);
            case "getUpdates" -> pollUpdates(body);
            case "sendMessage" -> {
                var messageId = messageIds.getAndIncrement();
                calls.add(new Call(method, body, messageId));
                yield Map.of(
                        "message_id",
                        messageId,
                        "chat",
                        chat(body.path("chat_id").asLong()));
            }
            default -> {
                calls.add(new Call(method, body, 0));
                yield true;
            }
        };
        var response = JSON.writeValueAsBytes(Map.of("ok", true, "result", result));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private List<Map<String, Object>> pollUpdates(JsonNode body) {
        var offset = body.path("offset").asLong();
        var timeout = Math.max(1, body.path("timeout").asLong());
        var result = new ArrayList<Map<String, Object>>();
        try {
            var first = updates.poll(timeout, TimeUnit.SECONDS);
            if (first != null) {
                result.add(first);
                updates.drainTo(result);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return result.stream()
                .filter(update -> ((Number) update.get("update_id")).longValue() >= offset)
                .toList();
    }

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public record Call(String method, JsonNode body, long messageId) {}
}
