package dev.pointer.device;

import dev.pointer.common.security.AuthPrincipal;
import dev.pointer.execution.DeviceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
class DeviceController {

    private final DeviceService devices;
    private final DeviceChannel channel;

    @GetMapping
    List<DeviceView> list(AuthPrincipal principal) {
        return devices.desktops(principal.userId()).stream()
                .map(device -> view(device, principal))
                .toList();
    }

    @PatchMapping("/{deviceId}")
    DeviceView rename(
            AuthPrincipal principal, @PathVariable UUID deviceId, @Valid @RequestBody RenameDeviceRequest request) {
        return view(devices.rename(principal.userId(), deviceId, request.name().strip()), principal);
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(AuthPrincipal principal, @PathVariable UUID deviceId) {
        devices.revokeOwned(principal.userId(), deviceId);
    }

    private DeviceView view(Device device, AuthPrincipal principal) {
        return new DeviceView(
                device.getId(),
                device.getName(),
                device.getPlatform(),
                device.getClientVersion(),
                device.getLastSeenAt(),
                device.getCreatedAt(),
                channel.isConnected(device.getId()),
                device.getId().equals(principal.deviceId()));
    }

    record DeviceView(
            UUID id,
            String name,
            Map<String, Object> platform,
            String clientVersion,
            Instant lastSeenAt,
            Instant createdAt,
            boolean online,
            boolean current) {}

    record RenameDeviceRequest(@NotBlank @Size(max = 100) String name) {}
}
