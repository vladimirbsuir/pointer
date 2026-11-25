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
    private static final long INLINE_IMAGE_CACHE_CHARS = 24L * 1024 * 1024;

    private final AttachmentRepository attachments;
    private final SessionService sessions;
    private final Clock clock;
    private final InlineImageCache inlineImages = new InlineImageCache(INLINE_IMAGE_CACHE_CHARS);

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
    public Optional<InlineImage> inlineImage(UUID attachmentId) {
        var cached = inlineImages.get(attachmentId);
        if (cached != null) {
            return Optional.of(cached);
        }
        var image = attachments
                .findById(attachmentId)
                .map(attachment -> new InlineImage(
                        "data:" + attachment.getMediaType() + ";base64,"
                                + Base64.getEncoder().encodeToString(attachment.getData()),
                        attachment.getWidth(),
                        attachment.getHeight()));
        image.ifPresent(found -> inlineImages.put(attachmentId, found));
        return image;
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

    private static final class InlineImageCache {

        private final long maxChars;
        private final Map<UUID, InlineImage> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long chars;

        InlineImageCache(long maxChars) {
            this.maxChars = maxChars;
        }

        synchronized InlineImage get(UUID id) {
            return entries.get(id);
        }

        synchronized void put(UUID id, InlineImage image) {
            if (image.dataUrl().length() > maxChars || entries.containsKey(id)) {
                return;
            }
            entries.put(id, image);
            chars += image.dataUrl().length();
            var iterator = entries.entrySet().iterator();
            while (chars > maxChars && iterator.hasNext()) {
                chars -= iterator.next().getValue().dataUrl().length();
                iterator.remove();
            }
        }
    }

    public record AttachmentResponse(UUID id, String mediaType, int sizeBytes) {}

    public record InlineImage(String dataUrl, Integer width, Integer height) {}
}
