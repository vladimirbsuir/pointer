package dev.pointer.device;

import dev.pointer.common.Ids;
import dev.pointer.common.crypto.Tokens;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private static final int SECRET_BYTES = 32;

    private final DeviceRepository devices;
    private final Clock clock;

    @Transactional
    public RunnerDevice createRunner(UUID userId, String name) {
        var secret = Tokens.random(SECRET_BYTES);
        var device = devices.save(Device.runner(Ids.newId(), userId, name, Tokens.sha256(secret), clock.instant()));
        return new RunnerDevice(device.getId(), secret);
    }

    @Transactional
    public String rotateRunnerSecret(UUID deviceId) {
        var device = devices.findById(deviceId).filter(Device::isRunner).orElseThrow();
        var secret = Tokens.random(SECRET_BYTES);
        device.rotateRunnerSecret(Tokens.sha256(secret), clock.instant());
        devices.save(device);
        return secret;
    }

    @Transactional
    public Optional<Device> authenticateRunner(UUID deviceId, String secret) {
        return devices.findById(deviceId)
                .filter(Device::isRunner)
                .filter(device -> !device.isRevoked())
                .filter(device -> Tokens.matches(secret, device.getRunnerSecretHash()))
                .map(device -> {
                    device.touch(clock.instant());
                    return device;
                });
    }

    @Transactional
    public void revoke(UUID deviceId) {
        devices.findById(deviceId).ifPresent(device -> device.revoke(clock.instant()));
    }

    @Transactional(readOnly = true)
    public boolean isRunner(UUID deviceId) {
        return deviceId != null
                && devices.findById(deviceId).filter(Device::isRunner).isPresent();
    }

    public record RunnerDevice(UUID id, String secret) {}

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
