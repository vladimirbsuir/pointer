package dev.pointer.session;

import dev.pointer.common.error.ApiException;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentService {

    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private final AttachmentRepository attachments;
    private final SessionService sessions;
    private final Clock clock;

    AttachmentService(AttachmentRepository attachments, SessionService sessions, Clock clock) {
        this.attachments = attachments;
        this.sessions = sessions;
        this.clock = clock;
    }

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
        var saved = attachments.save(new Attachment(sessionId, type, data, clock.instant()));
        return new AttachmentResponse(saved.getId(), saved.getMediaType(), saved.getSizeBytes());
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
        return attachments
                .findById(attachmentId)
                .map(attachment -> "data:" + attachment.getMediaType() + ";base64,"
                        + Base64.getEncoder().encodeToString(attachment.getData()));
    }

    public record AttachmentResponse(UUID id, String mediaType, int sizeBytes) {}
}
