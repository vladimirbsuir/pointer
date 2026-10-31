package dev.pointer.provider;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProviderModelRepository extends JpaRepository<ProviderModel, UUID> {

    List<ProviderModel> findByProviderIdInOrderByDisplayName(Collection<UUID> providerIds);

    List<ProviderModel> findByProviderId(UUID providerId);

    Optional<ProviderModel> findByIdAndProviderId(UUID id, UUID providerId);
}
