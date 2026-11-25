import json
import sys
import time

try:
    import gi

    gi.require_version("Atspi", "2.0")
    from gi.repository import Atspi, GLib
except (ImportError, ValueError) as error:
    print(json.dumps({"error": "tree_unavailable", "message": f"The AT-SPI bindings are missing: {error}"}))
    sys.exit(0)

METHOD_TIMEOUT_MS = 1500
STARTUP_TIMEOUT_MS = 3000
MAX_VISITS = 5000
DEADLINE_SECONDS = 12
MAX_LABEL_CHARS = 80
OBJECT_REPLACEMENT = "\ufffc"
CALL_ERRORS = (GLib.Error, RuntimeError)

STRUCTURAL_ROLES = {
    "application",
    "filler",
    "glass pane",
    "grouping",
    "invalid",
    "layered pane",
    "panel",
    "redundant object",
    "root pane",
    "scroll pane",
    "section",
    "split pane",
    "unknown",
    "viewport",
}
TEXT_ROLES = {"label", "static", "text"}
SECRET_ROLES = {"password text"}
REPORTED_STATES = [
    (Atspi.StateType.FOCUSED, "focused"),
    (Atspi.StateType.CHECKED, "checked"),
    (Atspi.StateType.SELECTED, "selected"),
    (Atspi.StateType.PRESSED, "pressed"),
    (Atspi.StateType.EXPANDED, "expanded"),
    (Atspi.StateType.MODAL, "modal"),
]


def shorten(text):
    text = " ".join((text or "").split())
    if len(text) > MAX_LABEL_CHARS:
        return text[: MAX_LABEL_CHARS - 1] + "…"
    return text


def role_of(node):
    return node.get_role_name() or "unknown"


def name_of(node):
    return shorten(node.get_name())


def clipped_bounds(node, screen):
    component = node.get_component_iface()
    if component is None:
        return None
    extents = Atspi.Component.get_extents(node, Atspi.CoordType.SCREEN)
    left = max(extents.x, 0)
    top = max(extents.y, 0)
    right = min(extents.x + extents.width, screen["width"])
    bottom = min(extents.y + extents.height, screen["height"])
    if right <= left or bottom <= top:
        return None
    return {"x": left, "y": top, "width": right - left, "height": bottom - top}


def text_of(node, role, name):
    if role in SECRET_ROLES or node.get_text_iface() is None:
        return None
    count = Atspi.Text.get_character_count(node)
    if count <= 0:
        return "" if is_editable(node) else None
    raw = Atspi.Text.get_text(node, 0, min(count, MAX_LABEL_CHARS * 2))
    if OBJECT_REPLACEMENT in raw:
        return None
    text = shorten(raw)
    if not text or text == name:
        return None
    return text


def value_of(node):
    if node.get_value_iface() is None:
        return None
    value = Atspi.Value.get_current_value(node)
    return int(value) if float(value).is_integer() else round(value, 3)


def is_editable(node):
    return node.get_state_set().contains(Atspi.StateType.EDITABLE)


def has_action(node):
    return node.get_action_iface() is not None and Atspi.Action.get_n_actions(node) > 0


def states_of(node, actionable):
    states = node.get_state_set()
    reported = [label for state, label in REPORTED_STATES if states.contains(state)]
    if states.contains(Atspi.StateType.EXPANDABLE) and not states.contains(Atspi.StateType.EXPANDED):
        reported.append("collapsed")
    enabled = states.contains(Atspi.StateType.ENABLED) or states.contains(Atspi.StateType.SENSITIVE)
    if actionable and not enabled:
        reported.append("disabled")
    return reported


def is_showing(node):
    return node.get_state_set().contains(Atspi.StateType.SHOWING)


def is_active(node):
    return node.get_state_set().contains(Atspi.StateType.ACTIVE)


def applications():
    desktop = Atspi.get_desktop(0)
    found = []
    for index in range(desktop.get_child_count()):
        application = desktop.get_child_at_index(index)
        if application is None:
            continue
        try:
            found.append(
                {
                    "node": application,
                    "name": application.get_name() or "",
                    "pid": application.get_process_id(),
                    "toolkit": application.get_toolkit_name() or "",
                }
            )
        except CALL_ERRORS:
            continue
    return found


class Walk:
    def __init__(self, screen, max_nodes, max_depth):
        self.screen = screen
        self.max_nodes = max_nodes
        self.max_depth = max_depth
        self.visits = 0
        self.deadline = time.monotonic() + DEADLINE_SECONDS
        self.nodes = []
        self.truncated = False

    def exhausted(self):
        if len(self.nodes) >= self.max_nodes or self.visits >= MAX_VISITS or time.monotonic() > self.deadline:
            self.truncated = True
            return True
        return False

    def window(self, application, window, index):
        self.visit(application, window, [index], 0, None)

    def visit(self, application, node, path, depth, parent):
        if self.exhausted():
            return
        self.visits += 1
        try:
            emitted = None
            if is_showing(node):
                emitted = self.emit(application, node, path, depth, parent)
                if emitted is False:
                    return
            child_depth = depth + 1 if emitted else depth
            child_parent = emitted if emitted else parent
            for index in range(node.get_child_count()):
                if self.exhausted():
                    return
                child = node.get_child_at_index(index)
                if child is not None:
                    self.visit(application, child, path + [index], child_depth, child_parent)
        except CALL_ERRORS:
            return

    def emit(self, application, node, path, depth, parent):
        role = role_of(node)
        name = name_of(node)
        bounds = clipped_bounds(node, self.screen)
        actionable = has_action(node)
        text = text_of(node, role, name)
        if bounds is None or not self.worth_emitting(role, name, text, is_editable(node), parent):
            return None
        if depth >= self.max_depth:
            self.truncated = True
            return False
        emitted = {
            "pid": application["pid"],
            "application": application["name"],
            "path": path,
            "depth": depth,
            "role": role,
            "name": name,
            **bounds,
        }
        if text is not None:
            emitted["text"] = text
        value = value_of(node)
        if value is not None:
            emitted["value"] = value
        states = states_of(node, actionable)
        if states:
            emitted["states"] = states
        self.nodes.append(emitted)
        return emitted

    @staticmethod
    def worth_emitting(role, name, text, editable, parent):
        if role in STRUCTURAL_ROLES and not name:
            return False
        if role in TEXT_ROLES and not editable and parent is not None:
            label = name or text or ""
            if not label or label in (parent.get("name"), parent.get("text")):
                return False
        return True


def windows_of(application):
    node = application["node"]
    for index in range(node.get_child_count()):
        window = node.get_child_at_index(index)
        if window is not None:
            yield index, window


def tree(arguments):
    Atspi.set_timeout(METHOD_TIMEOUT_MS, STARTUP_TIMEOUT_MS)
    screen = {"width": arguments["screenWidth"], "height": arguments["screenHeight"]}
    walk = Walk(screen, arguments["maxNodes"], arguments["maxDepth"])
    found = applications()
    scope = arguments.get("scope", "focused")
    targets = []
    if scope == "focused":
        for application in found:
            for index, window in windows_of(application):
                try:
                    if is_active(window) and is_showing(window):
                        targets.append((application, index, window))
                except CALL_ERRORS:
                    continue
        if not targets:
            scope = "all"
    if scope == "all":
        for application in found:
            for index, window in windows_of(application):
                targets.append((application, index, window))
    for application, index, window in targets:
        walk.window(application, window, index)
    listed = []
    for application in found:
        listed.append(
            {
                "name": application["name"],
                "pid": application["pid"],
                "toolkit": application["toolkit"],
                "windows": application["node"].get_child_count(),
            }
        )
    return {"scope": scope, "applications": listed, "nodes": walk.nodes, "truncated": walk.truncated}


def resolve(arguments):
    Atspi.set_timeout(METHOD_TIMEOUT_MS, STARTUP_TIMEOUT_MS)
    screen = {"width": arguments["screenWidth"], "height": arguments["screenHeight"]}
    for application in applications():
        if application["pid"] != arguments["pid"] or application["name"] != arguments["application"]:
            continue
        node = application["node"]
        try:
            for index in arguments["path"]:
                if index >= node.get_child_count():
                    return {"found": False}
                node = node.get_child_at_index(index)
                if node is None:
                    return {"found": False}
            if role_of(node) != arguments["role"] or name_of(node) != arguments["name"]:
                return {"found": False}
            if not is_showing(node):
                return {"found": False, "reason": "hidden"}
            bounds = clipped_bounds(node, screen)
            if bounds is None:
                return {"found": False, "reason": "offscreen"}
            return {"found": True, **bounds}
        except CALL_ERRORS:
            return {"found": False}
    return {"found": False}


def main():
    command = sys.argv[1]
    arguments = json.loads(sys.argv[2])
    handlers = {"tree": tree, "resolve": resolve}
    print(json.dumps(handlers[command](arguments), ensure_ascii=False))


main()
