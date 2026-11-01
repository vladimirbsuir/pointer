package dev.pointer.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public final class FakeOpenAiServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Response> queued = new ConcurrentLinkedQueue<>();
    private volatile List<String> models = List.of("fake-model");
    private volatile String requiredApiKey;

    public FakeOpenAiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/models", this::handleModels);
        server.createContext("/v1/chat/completions", this::handleChat);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public FakeOpenAiServer withModels(String... modelIds) {
        models = List.of(modelIds);
        return this;
    }

    public FakeOpenAiServer requireApiKey(String apiKey) {
        requiredApiKey = apiKey;
        return this;
    }

    public FakeOpenAiServer enqueueStream(Duration delayBetweenChunks, String... dataLines) {
        queued.add(new Response(200, delayBetweenChunks, List.of(dataLines)));
        return this;
    }

    public FakeOpenAiServer enqueueText(String... fragments) {
        var lines = new ArrayList<String>();
        for (var fragment : fragments) {
            lines.add("{\"choices\":[{\"delta\":{\"content\":" + quote(fragment) + "}}]}");
        }
        lines.add("{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}");
        lines.add("[DONE]");
        return enqueueStream(Duration.ZERO, lines.toArray(String[]::new));
    }

    public FakeOpenAiServer enqueueToolCall(String callId, String toolName, String argumentsJson) {
        var call = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":" + quote(callId)
                + ",\"type\":\"function\",\"function\":{\"name\":" + quote(toolName) + ",\"arguments\":"
                + quote(argumentsJson) + "}}]}}]}";
        return enqueueStream(
                Duration.ZERO, call, "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
    }

    public FakeOpenAiServer enqueueSilence(Duration silence) {
        queued.add(new Response(0, silence, List.of()));
        return this;
    }

    public FakeOpenAiServer enqueueError(int status, String body) {
        queued.add(new Response(status, Duration.ZERO, List.of(body)));
        return this;
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    private void handleModels(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        var data = String.join(
                ",", models.stream().map(id -> "{\"id\":" + quote(id) + "}").toList());
        respond(exchange, 200, "application/json", "{\"object\":\"list\",\"data\":[" + data + "]}");
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!authorized(exchange)) {
            return;
        }
        var response = queued.poll();
        if (response == null) {
            response = new Response(
                    200, Duration.ZERO, List.of("{\"choices\":[{\"delta\":{\"content\":\"Title\"}}]}", "[DONE]"));
        }
        if (response.status() == 0) {
            sleep(response.delay());
            exchange.close();
            return;
        }
        if (response.status() != 200) {
            respond(
                    exchange,
                    response.status(),
                    "application/json",
                    response.lines().getFirst());
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var body = exchange.getResponseBody()) {
            for (var line : response.lines()) {
                body.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                body.flush();
                sleep(response.delay());
            }
        } catch (IOException exception) {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        if (requiredApiKey == null) {
            return true;
        }
        var header = exchange.getRequestHeaders().getFirst("Authorization");
        if (("Bearer " + requiredApiKey).equals(header)) {
            return true;
        }
        respond(exchange, 401, "application/json", "{\"error\":{\"message\":\"Invalid API key\"}}");
        return false;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private record Response(int status, Duration delay, List<String> lines) {}
}
