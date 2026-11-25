package dev.pointer.container;

import java.time.Instant;

public final class DesktopViewDtos {

    private DesktopViewDtos() {}

    public record DesktopStatus(boolean available, boolean running, Integer width, Integer height) {}

    public record DesktopTicket(
            String ticket, Instant expiresAt, String path, String password, int width, int height) {}
}
