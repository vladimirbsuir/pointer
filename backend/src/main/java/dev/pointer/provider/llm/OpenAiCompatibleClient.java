package dev.pointer.provider.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class OpenAiCompatibleClient {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE = "[DONE]";
    private static final int MAX_ERROR_BYTES = 4096;

    private final JsonMapper json;

    public List<String> listModels(Endpoint endpoint) {
        var request = new HttpTransport.Request("GET", endpoint.url("/models"), headers(endpoint, false), null);
        try (var response = endpoint.transport().send(request, new Cancellation())) {
            ensureSuccess(response);
            var root = json.readTree(response.body());
            var models = new ArrayList<String>();
            root.path("data").forEach(model -> {
                var id = model.path("id").asString("");
                if (!id.isBlank()) {
                    models.add(id);
                }
            });
            return models;
        } catch (IOException exception) {
            throw new LlmException("provider_unreachable", exception.getMessage(), exception);
        }
    }

    public List<float[]> embed(Endpoint endpoint, String model, List<String> inputs, Cancellation cancellation) {
        var body = json.writeValueAsBytes(Map.of("model", model, "input", inputs));
        var request = new HttpTransport.Request("POST", endpoint.url("/embeddings"), headers(endpoint, false), body);
        try (var response = endpoint.transport().send(request, cancellation)) {
            ensureSuccess(response);
            var data = json.readTree(response.body()).path("data");
            var vectors = new float[inputs.size()][];
            for (var entry : data) {
                var index = entry.path("index").asInt(-1);
                if (index >= 0 && index < vectors.length) {
                    vectors[index] = vector(entry.path("embedding"));
                }
            }
            for (var vector : vectors) {
                if (vector == null || vector.length == 0) {
                    throw new LlmException("invalid_embeddings", "The provider returned incomplete embeddings", 0);
                }
            }
            return List.of(vectors);
        } catch (IOException exception) {
            throw new LlmException("provider_unreachable", exception.getMessage(), exception);
        }
    }

    public void stream(Endpoint endpoint, ChatRequest chatRequest, StreamListener listener, Cancellation cancellation) {
        var body = json.writeValueAsBytes(requestBody(chatRequest));
        var request =
                new HttpTransport.Request("POST", endpoint.url("/chat/completions"), headers(endpoint, true), body);
        try (var response = endpoint.transport().send(request, cancellation)) {
            cancellation.attach(response);
            ensureSuccess(response);
            var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            String line;
            while (!cancellation.isCancelled() && (line = reader.readLine()) != null) {
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                var data = line.substring(DATA_PREFIX.length()).strip();
                if (DONE.equals(data)) {
                    break;
                }
                handleChunk(json.readTree(data), listener);
            }
        } catch (IOException exception) {
            if (!cancellation.isCancelled()) {
                throw new LlmException("provider_unreachable", exception.getMessage(), exception);
            }
        } finally {
            cancellation.detach();
        }
    }

    private void handleChunk(JsonNode chunk, StreamListener listener) {
        if (chunk.has("error")) {
            throw new LlmException(
                    "provider_error", chunk.path("error").path("message").asString("Provider error"), 0);
        }
        for (var choice : chunk.path("choices")) {
            var delta = choice.path("delta");
            var content = delta.path("content");
            if (content.isString() && !content.asString().isEmpty()) {
                listener.onText(content.asString());
            }
            for (var field : List.of("reasoning_content", "reasoning")) {
                var reasoning = delta.path(field);
                if (reasoning.isString() && !reasoning.asString().isEmpty()) {
                    listener.onReasoning(reasoning.asString());
                }
            }
            for (var toolCall : delta.path("tool_calls")) {
                var function = toolCall.path("function");
                listener.onToolCallDelta(
                        toolCall.path("index").asInt(0),
                        textOrNull(toolCall.path("id")),
                        textOrNull(function.path("name")),
                        function.path("arguments").asString(""));
            }
        }
        var usage = chunk.path("usage");
        if (usage.isObject()) {
            listener.onUsage(new StreamListener.Usage(
                    usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0)));
        }
    }

    private Map<String, Object> requestBody(ChatRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        body.put("messages", request.messages().stream().map(this::message).toList());
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        if (!request.tools().isEmpty()) {
            body.put(
                    "tools",
                    request.tools().stream()
                            .map(tool -> Map.of(
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name", tool.name(),
                                            "description", tool.description(),
                                            "parameters", tool.parameters())))
                            .toList());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        return body;
    }

    private Map<String, Object> message(ChatMessage message) {
        var result = new HashMap<String, Object>();
        result.put("role", message.role().name().toLowerCase(Locale.ROOT));
        var content = message.content() == null ? "" : message.content();
        result.put("content", content.isEmpty() && !message.toolCalls().isEmpty() ? null : content);
        if (!message.imageUrls().isEmpty()) {
            var parts = new ArrayList<Map<String, Object>>();
            parts.add(Map.of("type", "text", "text", content));
            message.imageUrls().forEach(url -> parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url))));
            result.put("content", parts);
        }
        if (!message.toolCalls().isEmpty()) {
            result.put(
                    "tool_calls",
                    message.toolCalls().stream()
                            .map(call -> Map.of(
                                    "id",
                                    call.id(),
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", call.name(), "arguments", call.arguments())))
                            .toList());
        }
        if (message.toolCallId() != null) {
            result.put("tool_call_id", message.toolCallId());
        }
        return result;
    }

    private static Map<String, String> headers(Endpoint endpoint, boolean streaming) {
        var headers = new HashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", streaming ? "text/event-stream" : "application/json");
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            headers.put("Authorization", "Bearer " + endpoint.apiKey());
        }
        return headers;
    }

    private void ensureSuccess(HttpTransport.StreamResponse response) throws IOException {
        if (response.isSuccess()) {
            return;
        }
        var bytes = response.body().readNBytes(MAX_ERROR_BYTES);
        var message = new String(bytes, StandardCharsets.UTF_8);
        try {
            var node = json.readTree(bytes);
            var error = node.path("error");
            if (error.isObject() && error.path("message").isString()) {
                message = error.path("message").asString();
            } else if (error.isString()) {
                message = error.asString();
            }
        } catch (RuntimeException exception) {
            message = message.strip();
        }
        throw new LlmException(
                response.status() == 401 || response.status() == 403 ? "provider_unauthorized" : "provider_error",
                message.isBlank() ? "HTTP " + response.status() : message,
                response.status());
    }

    private static float[] vector(JsonNode node) {
        var vector = new float[node.size()];
        for (var index = 0; index < vector.length; index++) {
            vector[index] = (float) node.get(index).asDouble();
        }
        return vector;
    }

    private static String textOrNull(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }
}
