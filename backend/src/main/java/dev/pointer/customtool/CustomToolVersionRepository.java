package dev.pointer.customtool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CustomToolVersionRepository extends JpaRepository<CustomToolVersion, UUID> {

    Optional<CustomToolVersion> findByToolIdAndVersion(UUID toolId, int version);

    List<CustomToolVersion> findByToolIdOrderByVersionDesc(UUID toolId);

    @Query("select coalesce(max(v.version), 0) from CustomToolVersion v where v.toolId = :toolId")
    int latestVersion(UUID toolId);
}
