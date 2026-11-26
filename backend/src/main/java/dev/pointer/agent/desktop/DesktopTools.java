package dev.pointer.agent.desktop;

import dev.pointer.provider.ResolvedModel;
import java.util.Set;

public final class DesktopTools {

    public static final String START = "desktop_start";
    public static final String LAUNCH = "desktop_launch";
    public static final String STOP = "desktop_stop";
    public static final String SCREENSHOT = "desktop_screenshot";
    public static final String MOVE = "desktop_move";
    public static final String CLICK = "desktop_click";
    public static final String DRAG = "desktop_drag";
    public static final String SCROLL = "desktop_scroll";
    public static final String TYPE = "desktop_type";
    public static final String KEY = "desktop_key";
    public static final String WAIT = "desktop_wait";
    public static final String TREE = "desktop_tree";
    public static final String STATUS = "pointer.desktop_status";

    static final String PIXELS_NOTE =
            "Screenshot coordinates are pixels of the latest full screenshot, with the origin at its top left corner.";
    static final String GRID_NOTE = "Screenshot coordinates are on a 0 to 1000 grid on each axis of the latest full"
            + " screenshot, whatever its size: (0, 0) is its top left corner and (1000, 1000) its bottom right"
            + " corner.";

    static final String SANDBOX_NOTE = "In this session the display runs in a Linux sandbox container on the Pointer"
            + " server, not on the user's computer: the project files get there only with sandbox_sync, sandbox_run"
            + " runs commands in the sandbox and run_command runs them on the user's computer.";

    private static final String LOCAL_COMMAND_TOOL = "run_command";
    private static final String SANDBOX_COMMAND_TOOL = "sandbox_run";
    private static final Set<String> WITH_COORDINATES = Set.of(START, SCREENSHOT, MOVE, CLICK, DRAG, SCROLL, TREE);
    private static final Set<String> INPUTS = Set.of(MOVE, CLICK, DRAG, SCROLL, TYPE, KEY);

    private DesktopTools() {}

    public static String describe(String operation, String description, String coordinateSystem) {
        if (!WITH_COORDINATES.contains(operation)) {
            return description;
        }
        var note = ResolvedModel.NORMALIZED.equals(coordinateSystem) ? GRID_NOTE : PIXELS_NOTE;
        return description + " " + note;
    }

    public static String describe(String operation, String description, String coordinateSystem, boolean sandboxed) {
        var described = describe(operation, description, coordinateSystem);
        if (!sandboxed) {
            return described;
        }
        var routed = described.replace(LOCAL_COMMAND_TOOL, SANDBOX_COMMAND_TOOL);
        return START.equals(operation) ? routed + " " + SANDBOX_NOTE : routed;
    }

    static boolean isInput(String operation) {
        return INPUTS.contains(operation);
    }
}
