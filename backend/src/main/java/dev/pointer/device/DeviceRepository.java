package dev.pointer.device;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findBySessionIdAndKind(UUID sessionId, String kind);
}
