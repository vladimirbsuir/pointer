package dev.pointer.provider;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;

@Entity
@Table(name = "providers")
public class Provider extends BaseEntity {

    @Getter
    private UUID userId;

    @Getter
    private String name;

    private String adapter;

    @Getter
    private String baseUrl;

    private byte[] apiKeyEncrypted;

    @Getter(AccessLevel.PACKAGE)
    private String apiKeyHint;

    private boolean isLocal;
    private Instant createdAt;
    private Instant updatedAt;

    protected Provider() {}

    Provider(UUID userId, String name, String baseUrl, boolean local, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.name = name;
        this.adapter = "openai_compatible";
        this.baseUrl = baseUrl;
        this.isLocal = local;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isLocal() {
        return isLocal;
    }

    Optional<byte[]> getApiKeyEncrypted() {
        return Optional.ofNullable(apiKeyEncrypted);
    }

    void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    void changeBaseUrl(String baseUrl, Instant now) {
        this.baseUrl = baseUrl;
        this.updatedAt = now;
    }

    void markLocal(boolean local, Instant now) {
        this.isLocal = local;
        this.updatedAt = now;
    }

    void setApiKey(byte[] encrypted, String hint, Instant now) {
        this.apiKeyEncrypted = encrypted;
        this.apiKeyHint = hint;
        this.updatedAt = now;
    }
}
