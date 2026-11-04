package dev.pointer.command;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SlashCommandRepository extends JpaRepository<SlashCommand, UUID> {

    List<SlashCommand> findByUserIdOrderByName(UUID userId);

    Optional<SlashCommand> findByIdAndUserId(UUID id, UUID userId);

    Optional<SlashCommand> findByUserIdAndName(UUID userId, String name);
}
