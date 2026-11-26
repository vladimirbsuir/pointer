package dev.pointer.device;

import dev.pointer.common.Ids;
import dev.pointer.common.crypto.Tokens;
import dev.pointer.common.error.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private static final int SECRET_BYTES = 32;

    private final DeviceRepository devices;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

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
    public List<Device> desktops(UUID userId) {
        return devices.findByUserIdAndKindAndRevokedAtIsNullOrderByLastSeenAtDesc(userId, Device.DESKTOP);
    }

    @Transactional
    public Device rename(UUID userId, UUID deviceId, String name) {
        var device = ownedDesktop(userId, deviceId);
        device.rename(name, clock.instant());
        return device;
    }

    @Transactional
    public void revokeOwned(UUID userId, UUID deviceId) {
        ownedDesktop(userId, deviceId).revoke(clock.instant());
        publisher.publishEvent(new DeviceRevoked(userId, deviceId));
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(UUID deviceId) {
        return deviceId != null
                && devices.findById(deviceId).filter(Device::isRevoked).isPresent();
    }

    private Device ownedDesktop(UUID userId, UUID deviceId) {
        return devices.findById(deviceId)
                .filter(device -> device.getUserId().equals(userId))
                .filter(device -> !device.isRunner() && !device.isRevoked())
                .orElseThrow(() -> ApiException.notFound("device_not_found", "Device does not exist"));
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

    public record DeviceRevoked(UUID userId, UUID deviceId) {}

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
