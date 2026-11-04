package dev.pointer.user;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_settings")
@Getter
public class UserSettings {

    @Id
    private UUID userId;

    private String globalInstructions;
    private UUID defaultModelId;
    private UUID embeddingModelId;

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

    public Map<String, Object> getPreferences() {
        return Map.copyOf(preferences);
    }

    void changeGlobalInstructions(String globalInstructions, Instant now) {
        this.globalInstructions = globalInstructions;
        this.updatedAt = now;
    }

    void changeDefaultModel(UUID defaultModelId, Instant now) {
        this.defaultModelId = defaultModelId;
        this.updatedAt = now;
    }

    void changeEmbeddingModel(UUID embeddingModelId, Instant now) {
        this.embeddingModelId = embeddingModelId;
        this.updatedAt = now;
    }
}
