package dev.pointer.tool;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tools")
@Getter
public class ToolDefinition extends BaseEntity {

    public static final String READ = "read";
    public static final String WRITE = "write";
    public static final String EXECUTE = "execute";
    public static final String MCP = "mcp";
    public static final String MCP_OPERATION = "mcp.call";
    public static final String CUSTOM = "custom";
    public static final String CUSTOM_OPERATION = "custom_tool.run";

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
    private String mcpServer;
    private String mcpTool;
    private String interpreter;
    private UUID ownerUserId;
    private UUID projectId;
    private int version;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    protected ToolDefinition() {}

    private ToolDefinition(UUID id) {
        super(id);
    }

    static ToolDefinition mcp(
            UUID ownerUserId,
            String name,
            String server,
            String tool,
            String description,
            Map<String, Object> parametersSchema,
            boolean readOnly,
            Instant now) {
        var definition = new ToolDefinition(Ids.newId());
        definition.name = name;
        definition.operation = MCP_OPERATION;
        definition.defaultPolicy = "ask";
        definition.source = MCP;
        definition.mcpServer = server;
        definition.mcpTool = tool;
        definition.ownerUserId = ownerUserId;
        definition.version = 1;
        definition.enabled = true;
        definition.createdAt = now;
        definition.refreshMcp(description, parametersSchema, readOnly, now);
        return definition;
    }

    static ToolDefinition custom(
            UUID ownerUserId, UUID projectId, String name, CustomToolSpec spec, int version, Instant now) {
        var definition = new ToolDefinition(Ids.newId());
        definition.name = name;
        definition.kind = EXECUTE;
        definition.operation = CUSTOM_OPERATION;
        definition.source = CUSTOM;
        definition.ownerUserId = ownerUserId;
        definition.projectId = projectId;
        definition.enabled = true;
        definition.createdAt = now;
        definition.applyCustomVersion(spec, version, now);
        return definition;
    }

    void applyCustomVersion(CustomToolSpec spec, int version, Instant now) {
        this.description = spec.description();
        this.parametersSchema = spec.parametersSchema();
        this.interpreter = spec.interpreter();
        this.version = version;
        this.updatedAt = now;
    }

    void applyCustomSettings(String defaultPolicy, List<String> modes, boolean enabled, Instant now) {
        this.defaultPolicy = defaultPolicy;
        this.modes = List.copyOf(modes);
        this.enabled = enabled;
        this.updatedAt = now;
    }

    void refreshMcp(String description, Map<String, Object> parametersSchema, boolean readOnly, Instant now) {
        this.description = description == null || description.isBlank()
                ? "Tool " + mcpTool + " from the MCP server " + mcpServer
                : description;
        this.parametersSchema = parametersSchema == null ? Map.of("type", "object") : parametersSchema;
        this.kind = readOnly ? READ : EXECUTE;
        this.modes = readOnly ? List.of("ask", "plan", "agent") : List.of("agent");
        this.updatedAt = now;
    }

    public boolean isMcp() {
        return MCP.equals(source);
    }

    public boolean isCustom() {
        return CUSTOM.equals(source);
    }

    public boolean availableIn(String mode) {
        return modes.contains(mode);
    }
}
