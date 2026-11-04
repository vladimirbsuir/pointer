package dev.pointer.command;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slash_commands")
class SlashCommand extends BaseEntity {

    private UUID userId;
    private String name;
    private String description;
    private String prompt;
    private Instant createdAt;
    private Instant updatedAt;

    protected SlashCommand() {}

    SlashCommand(UUID userId, String name, String description, String prompt, Instant now) {
        super(Ids.newId());
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.prompt = prompt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    String getPrompt() {
        return prompt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void change(String name, String description, String prompt, Instant now) {
        this.name = name;
        this.description = description;
        this.prompt = prompt;
        this.updatedAt = now;
    }
}
