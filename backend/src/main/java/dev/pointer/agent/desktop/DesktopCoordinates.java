package dev.pointer.agent.desktop;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DesktopCoordinates {

    static final int GRID = 1000;

    private final DesktopGeometry geometry;
    private final boolean normalized;

    public DesktopCoordinates(DesktopGeometry geometry, boolean normalized) {
        this.geometry = geometry;
        this.normalized = normalized;
    }

    public int screenX(String name, Object value) {
        return toScreen(name, value, geometry.screenWidth(), geometry.imageWidth());
    }

    public int screenY(String name, Object value) {
        return toScreen(name, value, geometry.screenHeight(), geometry.imageHeight());
    }

    public int modelX(int screenX) {
        return toModel(screenX, geometry.screenWidth(), geometry.imageWidth());
    }

    public int modelY(int screenY) {
        return toModel(screenY, geometry.screenHeight(), geometry.imageHeight());
    }

    public Map<String, Object> mark(int screenX, int screenY) {
        var mark = new LinkedHashMap<String, Object>();
        mark.put("x", toImage(screenX, geometry.screenWidth(), geometry.imageWidth()));
        mark.put("y", toImage(screenY, geometry.screenHeight(), geometry.imageHeight()));
        return mark;
    }

    public Map<String, Object> screenRect(Object zoom) {
        if (!(zoom instanceof Map<?, ?> rect)) {
            throw new InvalidCoordinatesException("zoom must be an object with x, y, width and height");
        }
        var x = number("zoom.x", rect.get("x"));
        var y = number("zoom.y", rect.get("y"));
        var width = number("zoom.width", rect.get("width"));
        var height = number("zoom.height", rect.get("height"));
        if (width <= 0 || height <= 0) {
            throw new InvalidCoordinatesException("zoom.width and zoom.height must be positive");
        }
        checkSpan("zoom.x", "zoom.width", x, width, limit(geometry.imageWidth()));
        checkSpan("zoom.y", "zoom.height", y, height, limit(geometry.imageHeight()));
        var screenX = screenX("zoom.x", x);
        var screenY = screenY("zoom.y", y);
        var result = new LinkedHashMap<String, Object>();
        result.put("x", screenX);
        result.put("y", screenY);
        result.put("width", length(width, geometry.screenWidth(), geometry.imageWidth(), screenX));
        result.put("height", length(height, geometry.screenHeight(), geometry.imageHeight(), screenY));
        return result;
    }

    public Map<String, Object> modelRect(Map<?, ?> rect) {
        var x = ((Number) rect.get("x")).intValue();
        var y = ((Number) rect.get("y")).intValue();
        var width = ((Number) rect.get("width")).intValue();
        var height = ((Number) rect.get("height")).intValue();
        var result = new LinkedHashMap<String, Object>();
        result.put("x", modelX(x));
        result.put("y", modelY(y));
        result.put("width", Math.max(1, modelLength(width, geometry.screenWidth(), geometry.imageWidth())));
        result.put("height", Math.max(1, modelLength(height, geometry.screenHeight(), geometry.imageHeight())));
        return result;
    }

    public String describeRange() {
        return normalized
                ? "0 to " + GRID + " on each axis"
                : "0 to " + (geometry.imageWidth() - 1) + " horizontally and 0 to " + (geometry.imageHeight() - 1)
                        + " vertically";
    }

    private int toScreen(String name, Object value, int screen, int image) {
        var coordinate = number(name, value);
        var max = limit(image);
        if (coordinate < 0 || coordinate > max) {
            throw new InvalidCoordinatesException(name + " is " + format(coordinate)
                    + ", outside the screenshot: coordinates run from " + describeRange() + ".");
        }
        var scaled = normalized ? coordinate * (screen - 1) / GRID : coordinate * screen / image;
        return clamp((int) Math.round(scaled), screen - 1);
    }

    private int toModel(int screenValue, int screen, int image) {
        var scaled = normalized
                ? screen == 1 ? 0 : (double) screenValue * GRID / (screen - 1)
                : (double) screenValue * image / screen;
        return clamp((int) Math.round(scaled), limit(image));
    }

    private static int toImage(int screenValue, int screen, int image) {
        return clamp((int) Math.round((double) screenValue * image / screen), image - 1);
    }

    private int length(double modelLength, int screen, int image, int screenStart) {
        var scaled = normalized ? modelLength * screen / GRID : modelLength * screen / image;
        return Math.max(1, Math.min((int) Math.round(scaled), screen - screenStart));
    }

    private int modelLength(int screenLength, int screen, int image) {
        var scaled = normalized ? (double) screenLength * GRID / screen : (double) screenLength * image / screen;
        return (int) Math.round(scaled);
    }

    private void checkSpan(String startName, String lengthName, double start, double length, int max) {
        var end = normalized ? max : max + 1;
        if (start < 0 || start + length > end) {
            throw new InvalidCoordinatesException(startName + " plus " + lengthName
                    + " must stay inside the screenshot: coordinates run from " + describeRange() + ".");
        }
    }

    private int limit(int image) {
        return normalized ? GRID : image - 1;
    }

    private static double number(String name, Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw new InvalidCoordinatesException(name + " must be a number");
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    public static final class InvalidCoordinatesException extends RuntimeException {

        InvalidCoordinatesException(String message) {
            super(message);
        }
    }
}
