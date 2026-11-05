package dev.pointer.container;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RemoteWorkspaceRepository extends JpaRepository<RemoteWorkspace, UUID> {

    Optional<RemoteWorkspace> findBySessionId(UUID sessionId);

    Optional<RemoteWorkspace> findByDeviceId(UUID deviceId);

    List<RemoteWorkspace> findByUserIdAndSessionIdIn(UUID userId, Collection<UUID> sessionIds);

    List<RemoteWorkspace> findByStatusAndLastActiveAtBefore(String status, Instant before);

    List<RemoteWorkspace> findByStatusIn(Collection<String> statuses);
}
