package dev.pointer.user;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import lombok.Getter;

@Entity
@Table(name = "users")
@Getter
public class User extends BaseEntity {

    @Column(columnDefinition = "citext")
    private String email;

    private String passwordHash;
    private String displayName;
    private String locale;
    private Instant disabledAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected User() {}

    private User(String email, String passwordHash, String displayName, String locale, Instant now) {
        super(Ids.newId());
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.locale = locale;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static User withPassword(String email, String passwordHash, String displayName, String locale, Instant now) {
        return new User(email, passwordHash, displayName, locale, now);
    }

    public static User withoutPassword(String email, String displayName, String locale, Instant now) {
        return new User(email, null, displayName, locale, now);
    }

    public Optional<String> getPasswordHash() {
        return Optional.ofNullable(passwordHash);
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public boolean isDisabled() {
        return disabledAt != null;
    }

    public void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.updatedAt = now;
    }

    public void rename(String displayName, Instant now) {
        this.displayName = displayName;
        this.updatedAt = now;
    }

    public void changeLocale(String locale, Instant now) {
        this.locale = locale;
        this.updatedAt = now;
    }
}
