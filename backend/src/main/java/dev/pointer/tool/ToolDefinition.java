package dev.pointer.tool;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
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
    public static final String MCP = "mcp";
    public static final String MCP_OPERATION = "mcp.call";

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

    public String getMcpServer() {
        return mcpServer;
    }

    public String getMcpTool() {
        return mcpTool;
    }

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
