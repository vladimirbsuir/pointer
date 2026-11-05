package dev.pointer.customtool;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import dev.pointer.tool.CustomToolSpec;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "custom_tool_versions")
@Getter
class CustomToolVersion extends BaseEntity {

    private UUID toolId;
    private int version;
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> parametersSchema;

    private String interpreter;
    private String body;
    private UUID sessionId;
    private UUID itemId;
    private Instant createdAt;

    protected CustomToolVersion() {}

    CustomToolVersion(UUID toolId, int version, ToolProposal proposal, UUID sessionId, UUID itemId, Instant now) {
        super(Ids.newId());
        this.toolId = toolId;
        this.version = version;
        this.description = proposal.description();
        this.parametersSchema = proposal.parametersSchema();
        this.interpreter = proposal.interpreter();
        this.body = proposal.body();
        this.sessionId = sessionId;
        this.itemId = itemId;
        this.createdAt = now;
    }

    CustomToolSpec spec() {
        return new CustomToolSpec(description, parametersSchema, interpreter);
    }

    boolean sameAs(ToolProposal proposal) {
        return description.equals(proposal.description())
                && parametersSchema.equals(proposal.parametersSchema())
                && interpreter.equals(proposal.interpreter())
                && body.equals(proposal.body());
    }
}
