package dev.pointer.session;

import dev.pointer.common.error.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AttachmentService {

    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final long DATA_URL_CACHE_CHARS = 24L * 1024 * 1024;

    private final AttachmentRepository attachments;
    private final SessionService sessions;
    private final Clock clock;
    private final DataUrlCache dataUrls = new DataUrlCache(DATA_URL_CACHE_CHARS);

    @Transactional
    public AttachmentResponse upload(UUID userId, UUID sessionId, String mediaType, byte[] data) {
        sessions.owned(userId, sessionId);
        var type = mediaType == null ? "" : mediaType.split(";")[0].strip().toLowerCase();
        if (!IMAGE_TYPES.contains(type)) {
            throw ApiException.badRequest(
                    "unsupported_attachment", "Only PNG, JPEG, GIF and WebP images are supported");
        }
        if (data.length == 0 || data.length > MAX_IMAGE_BYTES) {
            throw ApiException.badRequest("attachment_too_large", "Images must be at most 5 MB");
        }
        var size = dimensions(data);
        var saved = attachments.save(new Attachment(
                sessionId,
                Attachment.ORIGIN_USER,
                type,
                data,
                size == null ? null : size[0],
                size == null ? null : size[1],
                clock.instant()));
        return new AttachmentResponse(saved.getId(), saved.getMediaType(), saved.getSizeBytes());
    }

    @Transactional
    public UUID storeScreenshot(UUID sessionId, String mediaType, byte[] data, int width, int height) {
        var saved = attachments.save(
                new Attachment(sessionId, Attachment.ORIGIN_DESKTOP, mediaType, data, width, height, clock.instant()));
        return saved.getId();
    }

    @Transactional(readOnly = true)
    public Attachment content(UUID userId, UUID sessionId, UUID attachmentId) {
        sessions.owned(userId, sessionId);
        return inSession(sessionId, attachmentId);
    }

    @Transactional(readOnly = true)
    public Attachment inSession(UUID sessionId, UUID attachmentId) {
        return attachments
                .findByIdAndSessionId(attachmentId, sessionId)
                .orElseThrow(() -> ApiException.notFound("attachment_not_found", "Attachment does not exist"));
    }

    @Transactional(readOnly = true)
    public Optional<String> dataUrl(UUID attachmentId) {
        var cached = dataUrls.get(attachmentId);
        if (cached != null) {
            return Optional.of(cached);
        }
        var url = attachments
                .findById(attachmentId)
                .map(attachment -> "data:" + attachment.getMediaType() + ";base64,"
                        + Base64.getEncoder().encodeToString(attachment.getData()));
        url.ifPresent(found -> dataUrls.put(attachmentId, found));
        return url;
    }

    private static int[] dimensions(byte[] data) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static final class DataUrlCache {

        private final long maxChars;
        private final Map<UUID, String> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long chars;

        DataUrlCache(long maxChars) {
            this.maxChars = maxChars;
        }

        synchronized String get(UUID id) {
            return entries.get(id);
        }

        synchronized void put(UUID id, String url) {
            if (url.length() > maxChars || entries.containsKey(id)) {
                return;
            }
            entries.put(id, url);
            chars += url.length();
            var iterator = entries.entrySet().iterator();
            while (chars > maxChars && iterator.hasNext()) {
                chars -= iterator.next().getValue().length();
                iterator.remove();
            }
        }
    }

    public record AttachmentResponse(UUID id, String mediaType, int sizeBytes) {}
}
