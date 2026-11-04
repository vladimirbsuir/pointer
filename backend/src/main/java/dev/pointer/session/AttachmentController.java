package dev.pointer.session;

import dev.pointer.common.security.AuthPrincipal;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/attachments")
class AttachmentController {

    private final AttachmentService service;

    AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @PostMapping(consumes = {"image/png", "image/jpeg", "image/gif", "image/webp"})
    @ResponseStatus(HttpStatus.CREATED)
    AttachmentService.AttachmentResponse upload(
            AuthPrincipal principal,
            @PathVariable UUID sessionId,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            @RequestBody byte[] data) {
        return service.upload(principal.userId(), sessionId, contentType, data);
    }

    @GetMapping("/{attachmentId}")
    ResponseEntity<byte[]> download(
            AuthPrincipal principal, @PathVariable UUID sessionId, @PathVariable UUID attachmentId) {
        var attachment = service.content(principal.userId(), sessionId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getMediaType()))
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(attachment.getData());
    }
}
