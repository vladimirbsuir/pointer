package dev.pointer.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByUserIdOrderByCreatedAt(UUID userId);

    Optional<Project> findByIdAndUserId(UUID id, UUID userId);
}
