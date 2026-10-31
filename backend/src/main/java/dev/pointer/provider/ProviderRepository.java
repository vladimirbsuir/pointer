package dev.pointer.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProviderRepository extends JpaRepository<Provider, UUID> {

    List<Provider> findByUserIdOrderByCreatedAt(UUID userId);

    Optional<Provider> findByIdAndUserId(UUID id, UUID userId);
}
