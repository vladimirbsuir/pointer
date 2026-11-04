package dev.pointer.device;

import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository devices;
    private final Clock clock;

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
