package dev.pointer.device;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeviceRepository extends JpaRepository<Device, UUID> {}
