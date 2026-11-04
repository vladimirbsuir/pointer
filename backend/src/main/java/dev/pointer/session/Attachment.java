package dev.pointer.session;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "attachments")
@Getter
public class Attachment extends BaseEntity {

    private UUID sessionId;
    private String mediaType;
    private int sizeBytes;
    private byte[] data;
    private Instant createdAt;

    protected Attachment() {}

    Attachment(UUID sessionId, String mediaType, byte[] data, Instant now) {
        super(Ids.newId());
        this.sessionId = sessionId;
        this.mediaType = mediaType;
        this.sizeBytes = data.length;
        this.data = data;
        this.createdAt = now;
    }
}
