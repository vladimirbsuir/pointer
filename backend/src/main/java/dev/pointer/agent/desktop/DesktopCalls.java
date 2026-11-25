package dev.pointer.agent.desktop;

import dev.pointer.execution.ExecutionListener;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionResult;
import dev.pointer.execution.ExecutionService;
import dev.pointer.provider.ResolvedModel;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.session.AttachmentService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(DesktopProperties.class)
public class DesktopCalls {

    public static final String SCREENSHOT_NOTE = "Screenshot attached.";
    static final String DISPLAY_NOT_STARTED = "display_not_started";
    static final String IMAGE_TOO_LARGE = "image_too_large";
    static final String INVALID_IMAGE = "invalid_image";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WAIT_MARGIN = Duration.ofSeconds(10);
    private static final long DEFAULT_CONDITION_WAIT_MS = 60_000;
    private static final long MAX_CONDITION_WAIT_MS = 120_000;
    private static final long MAX_PLAIN_WAIT_MS = 10_000;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private final ExecutionService execution;
    private final AttachmentService attachments;
    private final DesktopProperties properties;
    private final Map<UUID, DesktopGeometry> geometries = new ConcurrentHashMap<>();

    DesktopCalls(ExecutionService execution, AttachmentService attachments, DesktopProperties properties) {
        this.execution = execution;
        this.attachments = attachments;
        this.properties = properties;
    }

    public Prepared prepare(CallContext context, String operation, Map<String, Object> arguments) {
        var prepared = new LinkedHashMap<>(arguments);
        var points = new ArrayList<int[]>();
        DesktopGeometry geometry = null;
        try {
            switch (operation) {
                case DesktopTools.START -> {
                    prepared.putIfAbsent("width", properties.defaultWidth());
                    prepared.putIfAbsent("height", properties.defaultHeight());
                    prepared.put("maxImageWidth", properties.maxImageWidth());
                    prepared.put("maxImageHeight", properties.maxImageHeight());
                    prepared.put("maxImageBytes", properties.maxImageBytes().toBytes());
                }
                case DesktopTools.MOVE, DesktopTools.CLICK, DesktopTools.SCROLL -> {
                    geometry = geometry(context).orElse(null);
                    if (geometry == null) {
                        return Prepared.failure(notStarted());
                    }
                    var coordinates = coordinates(context, geometry);
                    var x = coordinates.screenX("x", arguments.get("x"));
                    var y = coordinates.screenY("y", arguments.get("y"));
                    prepared.put("x", x);
                    prepared.put("y", y);
                    points.add(new int[] {x, y});
                }
                case DesktopTools.DRAG -> {
                    geometry = geometry(context).orElse(null);
                    if (geometry == null) {
                        return Prepared.failure(notStarted());
                    }
                    var coordinates = coordinates(context, geometry);
                    var fromX = coordinates.screenX("fromX", arguments.get("fromX"));
                    var fromY = coordinates.screenY("fromY", arguments.get("fromY"));
                    var toX = coordinates.screenX("toX", arguments.get("toX"));
                    var toY = coordinates.screenY("toY", arguments.get("toY"));
                    prepared.put("fromX", fromX);
                    prepared.put("fromY", fromY);
                    prepared.put("toX", toX);
                    prepared.put("toY", toY);
                    points.add(new int[] {fromX, fromY});
                    points.add(new int[] {toX, toY});
                }
                case DesktopTools.SCREENSHOT -> {
                    if (arguments.get("zoom") != null) {
                        geometry = geometry(context).orElse(null);
                        if (geometry == null) {
                            return Prepared.failure(notStarted());
                        }
                        prepared.put("zoom", coordinates(context, geometry).screenRect(arguments.get("zoom")));
                    }
                }
                default -> {}
            }
        } catch (DesktopCoordinates.InvalidCoordinatesException exception) {
            return Prepared.failure(
                    ExecutionResult.failure(ExecutionResult.REJECTED, "invalid_coordinates", exception.getMessage()));
        }
        if (DesktopTools.isInput(operation) || DesktopTools.WAIT.equals(operation)) {
            prepared.put("settleMs", properties.settleMs());
        }
        return new Prepared(prepared, List.copyOf(points), geometry, null);
    }

    public Finished finish(CallContext context, String operation, Prepared prepared, ExecutionResult result) {
        if (!result.succeeded()) {
            return new Finished(result, List.of(), List.of());
        }
        var model = new LinkedHashMap<>(result.result());
        var images = imagesOf(model.remove("images"));
        var geometry = prepared.geometry();
        if (DesktopTools.START.equals(operation)) {
            geometry = DesktopGeometry.fromStatus(result.result()).orElse(geometry);
        }
        for (var image : images) {
            var fromImage = DesktopGeometry.fromImage(image);
            if (fromImage.isPresent()) {
                geometry = fromImage.get();
            }
        }
        if (geometry != null) {
            geometries.put(context.sessionId(), geometry);
            toModelSpace(operation, model, coordinates(context, geometry), context.coordinateSystem());
        }
        var stored = new ArrayList<Map<String, Object>>();
        for (var image : images) {
            var outcome = store(context.sessionId(), image);
            if (outcome.failure() != null) {
                return new Finished(outcome.failure(), List.of(), List.of());
            }
            stored.add(outcome.reference());
        }
        var marks = new ArrayList<Map<String, Object>>();
        var hasFullImage = stored.stream().anyMatch(image -> !Boolean.TRUE.equals(image.get("zoom")));
        if (hasFullImage && geometry != null) {
            var coordinates = coordinates(context, geometry);
            prepared.points().forEach(point -> marks.add(coordinates.mark(point[0], point[1])));
        }
        return new Finished(
                new ExecutionResult(result.status(), model, result.error(), result.fileChanges()),
                List.copyOf(stored),
                List.copyOf(marks));
    }

    public Duration timeout(String operation, Map<String, Object> arguments) {
        if (!DesktopTools.WAIT.equals(operation)) {
            return DEFAULT_TIMEOUT;
        }
        var conditional = arguments.get("untilWindow") != null || arguments.get("untilLog") != null;
        var requested = arguments.get("ms") instanceof Number ms ? ms.longValue() : DEFAULT_CONDITION_WAIT_MS;
        var bounded = Math.max(0, Math.min(requested, conditional ? MAX_CONDITION_WAIT_MS : MAX_PLAIN_WAIT_MS));
        return Duration.ofMillis(bounded).plus(WAIT_MARGIN);
    }

    private Optional<DesktopGeometry> geometry(CallContext context) {
        var known = geometries.get(context.sessionId());
        if (known != null) {
            return Optional.of(known);
        }
        var status = execution.execute(
                new ExecutionRequest(
                        context.sessionId(),
                        context.projectId(),
                        context.deviceId(),
                        DesktopTools.STATUS,
                        Map.of(),
                        false,
                        STATUS_TIMEOUT,
                        context.worktree()),
                new ExecutionListener() {},
                context.cancellation());
        if (!status.succeeded() || !Boolean.TRUE.equals(status.result().get("running"))) {
            return Optional.empty();
        }
        var geometry = DesktopGeometry.fromStatus(status.result());
        geometry.ifPresent(found -> geometries.put(context.sessionId(), found));
        return geometry;
    }

    private static DesktopCoordinates coordinates(CallContext context, DesktopGeometry geometry) {
        return new DesktopCoordinates(geometry, ResolvedModel.NORMALIZED.equals(context.coordinateSystem()));
    }

    private static void toModelSpace(
            String operation, Map<String, Object> result, DesktopCoordinates coordinates, String coordinateSystem) {
        switch (operation) {
            case DesktopTools.MOVE, DesktopTools.CLICK, DesktopTools.SCROLL -> {
                convertX(result, "x", coordinates);
                convertY(result, "y", coordinates);
            }
            case DesktopTools.DRAG -> {
                convertX(result, "fromX", coordinates);
                convertY(result, "fromY", coordinates);
                convertX(result, "toX", coordinates);
                convertY(result, "toY", coordinates);
            }
            case DesktopTools.SCREENSHOT -> {
                result.remove("screenWidth");
                result.remove("screenHeight");
                if (result.get("zoom") instanceof Map<?, ?> zoom) {
                    result.put("zoom", coordinates.modelRect(zoom));
                }
            }
            case DesktopTools.START -> {
                var started = new LinkedHashMap<String, Object>();
                started.put("display", result.get("display"));
                started.put("width", result.getOrDefault("imageWidth", result.get("width")));
                started.put("height", result.getOrDefault("imageHeight", result.get("height")));
                started.put(
                        "coordinates",
                        ResolvedModel.NORMALIZED.equals(coordinateSystem)
                                ? ResolvedModel.NORMALIZED
                                : ResolvedModel.PIXELS);
                started.put("windowManager", result.get("windowManager"));
                started.put("alreadyRunning", result.get("alreadyRunning"));
                result.clear();
                result.putAll(started);
            }
            default -> {}
        }
    }

    private static void convertX(Map<String, Object> result, String name, DesktopCoordinates coordinates) {
        if (result.get(name) instanceof Number value) {
            result.put(name, coordinates.modelX(value.intValue()));
        }
    }

    private static void convertY(Map<String, Object> result, String name, DesktopCoordinates coordinates) {
        if (result.get(name) instanceof Number value) {
            result.put(name, coordinates.modelY(value.intValue()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> imagesOf(Object images) {
        if (!(images instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(image -> image instanceof Map<?, ?>)
                .map(image -> (Map<String, Object>) image)
                .toList();
    }

    private StoredImage store(UUID sessionId, Map<String, Object> image) {
        var mediaType = String.valueOf(image.get("mediaType"));
        var signature = switch (mediaType) {
            case "image/png" -> PNG_SIGNATURE;
            case "image/jpeg" -> JPEG_SIGNATURE;
            default -> null;
        };
        if (signature == null
                || !(image.get("data") instanceof String data)
                || !(image.get("width") instanceof Number width)
                || !(image.get("height") instanceof Number height)) {
            return invalid("The executor returned a screenshot in an unknown format");
        }
        var limit = properties.maxImageBytes().toBytes();
        if (data.length() > limit / 3 * 4 + 4) {
            return tooLarge(limit);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException exception) {
            return invalid("The screenshot data is not valid base64");
        }
        if (bytes.length > limit) {
            return tooLarge(limit);
        }
        if (!startsWith(bytes, signature)
                || width.intValue() < 1
                || height.intValue() < 1
                || width.intValue() > properties.maxImageWidth()
                || height.intValue() > properties.maxImageHeight()) {
            return invalid("The screenshot does not match its declared type or size");
        }
        var id = attachments.storeScreenshot(sessionId, mediaType, bytes, width.intValue(), height.intValue());
        var reference = new LinkedHashMap<String, Object>();
        reference.put("attachmentId", id);
        reference.put("mediaType", mediaType);
        reference.put("width", width.intValue());
        reference.put("height", height.intValue());
        reference.put("zoom", image.get("zoom") != null);
        return new StoredImage(reference, null);
    }

    private static StoredImage invalid(String message) {
        return new StoredImage(null, ExecutionResult.failure(ExecutionResult.FAILED, INVALID_IMAGE, message));
    }

    private static StoredImage tooLarge(long limit) {
        return new StoredImage(
                null,
                ExecutionResult.failure(
                        ExecutionResult.FAILED, IMAGE_TOO_LARGE, "The screenshot is larger than " + limit + " bytes"));
    }

    private record StoredImage(Map<String, Object> reference, ExecutionResult failure) {}

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (var index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static ExecutionResult notStarted() {
        return ExecutionResult.failure(
                ExecutionResult.FAILED, DISPLAY_NOT_STARTED, "The display is not running. Call desktop_start first");
    }

    public record CallContext(
            UUID sessionId,
            UUID projectId,
            UUID deviceId,
            String worktree,
            Cancellation cancellation,
            String coordinateSystem) {}

    public record Prepared(
            Map<String, Object> arguments, List<int[]> points, DesktopGeometry geometry, ExecutionResult failure) {

        static Prepared failure(ExecutionResult failure) {
            return new Prepared(Map.of(), List.of(), null, failure);
        }
    }

    public record Finished(ExecutionResult result, List<Map<String, Object>> images, List<Map<String, Object>> marks) {}
}
