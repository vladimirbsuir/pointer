package dev.pointer.user;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "user_identities")
@Getter
public class UserIdentity extends BaseEntity {

    public static final String GITHUB = "github";

    private UUID userId;
    private String provider;
    private String providerUserId;
    private String providerLogin;
    private byte[] accessTokenEncrypted;
    private String scopes;
    private Instant createdAt;
    private Instant updatedAt;

    protected UserIdentity() {}

    public UserIdentity(UUID userId, String provider, String providerUserId, String providerLogin, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.providerLogin = providerLogin;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateLogin(String providerLogin, Instant now) {
        this.providerLogin = providerLogin;
        this.updatedAt = now;
    }
}
