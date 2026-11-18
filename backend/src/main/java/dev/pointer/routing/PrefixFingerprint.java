package dev.pointer.routing;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.provider.llm.ChatRequest;
import dev.pointer.provider.llm.TokenEstimate;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PrefixFingerprint {

    static final int MAX_TRACKED = 2_000;
    private static final char SEPARATOR = '\u0000';

    private final JsonMapper json;
    private final Map<Key, Fingerprint> latest = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Fingerprint> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public PrefixFingerprint(JsonMapper json) {
        this.json = json;
    }

    public PrefixReuse measure(UUID sessionId, String purpose, UUID modelId, ChatRequest request) {
        var current = fingerprint(modelId, request);
        Fingerprint previous;
        synchronized (latest) {
            previous = latest.put(new Key(sessionId, purpose), current);
        }
        if (previous == null) {
            return new PrefixReuse(current.totalTokens(), null);
        }
        if (!Objects.equals(previous.modelId(), modelId)) {
            return new PrefixReuse(current.totalTokens(), 0L);
        }
        return new PrefixReuse(current.totalTokens(), current.commonPrefixTokens(previous));
    }

    private Fingerprint fingerprint(UUID modelId, ChatRequest request) {
        var segments = new ArrayList<Segment>();
        if (!request.tools().isEmpty()) {
            var tools = json.writeValueAsString(request.tools());
            segments.add(new Segment(hash(tools), TokenEstimate.text(tools)));
        }
        for (var message : request.messages()) {
            segments.add(new Segment(hash(canonical(message)), TokenEstimate.message(message)));
        }
        return new Fingerprint(modelId, List.copyOf(segments));
    }

    private static String canonical(ChatMessage message) {
        var text = new StringBuilder(message.role().name())
                .append(SEPARATOR)
                .append(Objects.requireNonNullElse(message.content(), ""))
                .append(SEPARATOR)
                .append(Objects.requireNonNullElse(message.toolCallId(), ""));
        message.toolCalls()
                .forEach(call -> text.append(SEPARATOR)
                        .append(call.id())
                        .append(SEPARATOR)
                        .append(call.name())
                        .append(SEPARATOR)
                        .append(call.arguments()));
        message.imageUrls().forEach(url -> text.append(SEPARATOR).append(url));
        return text.toString();
    }

    private static long hash(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record PrefixReuse(long prefixTokens, Long prefixReuseTokens) {}

    private record Key(UUID sessionId, String purpose) {}

    private record Segment(long hash, int tokens) {}

    private record Fingerprint(UUID modelId, List<Segment> segments) {

        long totalTokens() {
            return segments.stream().mapToLong(Segment::tokens).sum();
        }

        long commonPrefixTokens(Fingerprint other) {
            var reused = 0L;
            var count = Math.min(segments.size(), other.segments.size());
            for (var index = 0; index < count; index++) {
                var segment = segments.get(index);
                if (segment.hash() != other.segments.get(index).hash()) {
                    break;
                }
                reused += segment.tokens();
            }
            return reused;
        }
    }
}
