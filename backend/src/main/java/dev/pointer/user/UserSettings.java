package dev.pointer.user;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    private UUID userId;

    private String globalInstructions;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> preferences;

    private Instant updatedAt;

    protected UserSettings() {}

    public UserSettings(UUID userId, Instant now) {
        this.userId = userId;
        this.globalInstructions = "";
        this.preferences = new HashMap<>();
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getGlobalInstructions() {
        return globalInstructions;
    }

    public Map<String, Object> getPreferences() {
        return Map.copyOf(preferences);
    }
}
