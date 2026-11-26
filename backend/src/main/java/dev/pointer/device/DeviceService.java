package dev.pointer.device;

import dev.pointer.common.Ids;
import dev.pointer.common.crypto.Tokens;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    public void bindToSession(UUID deviceId, UUID sessionId) {
        devices.findById(deviceId)
                .filter(Device::isRunner)
                .ifPresent(device -> device.bindToSession(sessionId, clock.instant()));
    }

    @Transactional
    public List<UUID> revokeRunnersOf(UUID sessionId) {
        var now = clock.instant();
        return devices.findBySessionIdAndKind(sessionId, Device.RUNNER).stream()
                .map(device -> {
                    device.revoke(now);
                    return device.getId();
                })
                .toList();
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

    @Transactional(readOnly = true)
    public boolean isActiveRunner(UUID deviceId) {
        return deviceId != null
                && devices.findById(deviceId)
                        .filter(Device::isRunner)
                        .filter(device -> !device.isRevoked())
                        .isPresent();
    }

    @Transactional
    public void reportCapabilities(UUID userId, UUID deviceId, Set<String> capabilities) {
        devices.findById(deviceId)
                .filter(device -> device.getUserId().equals(userId))
                .ifPresent(device -> device.reportCapabilities(capabilities, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Set<String> storedCapabilities(UUID deviceId) {
        return deviceId == null
                ? Set.of()
                : devices.findById(deviceId).map(Device::capabilitySet).orElse(Set.of());
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
