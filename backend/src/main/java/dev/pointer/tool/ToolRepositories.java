package dev.pointer.tool;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ToolRepository extends JpaRepository<ToolDefinition, UUID> {

    @Query("""
            select t from ToolDefinition t where t.enabled = true
            and (t.ownerUserId is null or t.ownerUserId = :userId)
            and (t.projectId is null or t.projectId = :projectId)
            order by t.name
            """)
    List<ToolDefinition> findAvailable(UUID userId, UUID projectId);

    @Query("select t from ToolDefinition t where t.ownerUserId is null order by t.name")
    List<ToolDefinition> findBuiltin();

    @Query("select t from ToolDefinition t where t.ownerUserId = :userId and t.source = 'mcp'")
    List<ToolDefinition> findMcp(UUID userId);

    @Query("""
            select t from ToolDefinition t where t.name = :name
            and (t.ownerUserId is null or (t.ownerUserId = :userId and t.source = 'mcp'))
            """)
    List<ToolDefinition> findNonCustomByName(UUID userId, String name);

    @Query("""
            select t from ToolDefinition t where t.source = 'custom' and t.ownerUserId = :userId
            and (t.projectId is null or t.projectId = :projectId)
            order by t.name
            """)
    List<ToolDefinition> findCustomVisible(UUID userId, UUID projectId);

    @Query("select t from ToolDefinition t where t.source = 'custom' and t.ownerUserId = :userId order by t.name")
    List<ToolDefinition> findCustom(UUID userId);
}

@Entity
@Table(name = "permission_policies")
class PermissionPolicy extends BaseEntity {

    private UUID userId;
    private UUID projectId;
    private UUID toolId;
    private String policy;
    private Instant createdAt;
    private Instant updatedAt;

    protected PermissionPolicy() {}

    PermissionPolicy(UUID userId, UUID projectId, UUID toolId, String policy, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.projectId = projectId;
        this.toolId = toolId;
        this.policy = policy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getToolId() {
        return toolId;
    }

    String getPolicy() {
        return policy;
    }
}

interface PermissionPolicyRepository extends JpaRepository<PermissionPolicy, UUID> {

    @Query(
            "select p from PermissionPolicy p where p.userId = :userId and (p.projectId is null or p.projectId = :projectId)")
    List<PermissionPolicy> findApplicable(UUID userId, UUID projectId);

    @Query("select p from PermissionPolicy p where p.userId = :userId and p.projectId is not distinct from :projectId")
    List<PermissionPolicy> findExact(UUID userId, UUID projectId);

    @Modifying
    @Query("delete from PermissionPolicy p where p.userId = :userId and p.projectId is not distinct from :projectId")
    void deleteExact(UUID userId, UUID projectId);
}

@Entity
@Table(name = "command_rules")
class CommandRule extends BaseEntity {

    private UUID userId;
    private UUID projectId;
    private String prefix;
    private String policy;
    private Instant createdAt;

    protected CommandRule() {}

    CommandRule(UUID userId, UUID projectId, String prefix, String policy, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.projectId = projectId;
        this.prefix = prefix;
        this.policy = policy;
        this.createdAt = now;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getPrefix() {
        return prefix;
    }

    String getPolicy() {
        return policy;
    }
}

interface CommandRuleRepository extends JpaRepository<CommandRule, UUID> {

    @Query("select r from CommandRule r where r.userId = :userId and (r.projectId is null or r.projectId = :projectId)")
    List<CommandRule> findApplicable(UUID userId, UUID projectId);

    @Query(
            "select r from CommandRule r where r.userId = :userId and r.projectId is not distinct from :projectId order by r.createdAt")
    List<CommandRule> findExact(UUID userId, UUID projectId);

    @Modifying
    @Query("delete from CommandRule r where r.userId = :userId and r.projectId is not distinct from :projectId")
    void deleteExact(UUID userId, UUID projectId);
}

@Entity
@Table(name = "session_grants")
class SessionGrant extends BaseEntity {

    private UUID sessionId;
    private UUID toolId;
    private String commandPrefix;
    private Instant createdAt;

    protected SessionGrant() {}

    SessionGrant(UUID sessionId, UUID toolId, String commandPrefix, Instant now) {
        super(Ids.newId());
        this.sessionId = sessionId;
        this.toolId = toolId;
        this.commandPrefix = commandPrefix;
        this.createdAt = now;
    }

    UUID getToolId() {
        return toolId;
    }

    String getCommandPrefix() {
        return commandPrefix;
    }
}

interface SessionGrantRepository extends JpaRepository<SessionGrant, UUID> {

    List<SessionGrant> findBySessionId(UUID sessionId);

    @Modifying
    @Query("delete from SessionGrant g where g.toolId = :toolId")
    void deleteByToolId(UUID toolId);
}
