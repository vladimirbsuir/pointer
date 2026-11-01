package dev.pointer.tool;

import dev.pointer.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tools")
public class ToolDefinition extends BaseEntity {

    public static final String READ = "read";
    public static final String WRITE = "write";
    public static final String EXECUTE = "execute";

    private String name;
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> parametersSchema;

    private String kind;
    private String operation;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> modes;

    private String defaultPolicy;
    private String source;
    private UUID ownerUserId;
    private UUID projectId;
    private int version;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    protected ToolDefinition() {}

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getParametersSchema() {
        return parametersSchema;
    }

    public String getKind() {
        return kind;
    }

    public String getOperation() {
        return operation;
    }

    public List<String> getModes() {
        return modes;
    }

    public String getDefaultPolicy() {
        return defaultPolicy;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean availableIn(String mode) {
        return modes.contains(mode);
    }
}
