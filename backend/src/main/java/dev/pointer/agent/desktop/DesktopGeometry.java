package dev.pointer.agent.desktop;

import java.util.Map;
import java.util.Optional;

public record DesktopGeometry(int screenWidth, int screenHeight, int imageWidth, int imageHeight) {

    public DesktopGeometry {
        if (screenWidth < 1 || screenHeight < 1 || imageWidth < 1 || imageHeight < 1) {
            throw new IllegalArgumentException("Display and image sizes must be positive");
        }
    }

    static Optional<DesktopGeometry> fromStatus(Map<String, Object> status) {
        return of(status.get("width"), status.get("height"), status.get("imageWidth"), status.get("imageHeight"));
    }

    static Optional<DesktopGeometry> fromImage(Map<String, Object> image) {
        if (image.get("zoom") != null) {
            return Optional.empty();
        }
        return of(image.get("screenWidth"), image.get("screenHeight"), image.get("width"), image.get("height"));
    }

    private static Optional<DesktopGeometry> of(Object screenWidth, Object screenHeight, Object width, Object height) {
        if (screenWidth instanceof Number sw
                && screenHeight instanceof Number sh
                && width instanceof Number w
                && height instanceof Number h
                && sw.intValue() > 0
                && sh.intValue() > 0
                && w.intValue() > 0
                && h.intValue() > 0) {
            return Optional.of(new DesktopGeometry(sw.intValue(), sh.intValue(), w.intValue(), h.intValue()));
        }
        return Optional.empty();
    }
}
