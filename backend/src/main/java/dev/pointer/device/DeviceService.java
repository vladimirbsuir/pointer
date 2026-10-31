package dev.pointer.device;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final Clock clock;

    DeviceService(DeviceRepository devices, Clock clock) {
        this.devices = devices;
        this.clock = clock;
    }

    @Transactional
    public Device register(UUID userId, DeviceInfo info) {
        var now = clock.instant();
        var device = devices.findById(info.id())
                .map(existing -> {
                    existing.update(userId, info, now);
                    return existing;
                })
                .orElseGet(() -> new Device(info.id(), userId, info, now));
        return devices.save(device);
    }
}
