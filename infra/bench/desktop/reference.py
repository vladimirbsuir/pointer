import argparse
import json
import os
import subprocess
import sys
import time
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import tasks as catalog

READER = Path("/repo/desktop/src-tauri/crates/pointer-executor/src/desktop/atspi.py")
DISPLAY = ":99"
RUNTIME = Path("/tmp/runtime-99")
JAVA_ATK_WRAPPER = "/usr/share/java/java-atk-wrapper.jar"
TILE = 32
IDLE_SECONDS = 0.6
SETTLE_WINDOW_SECONDS = 2.5
LATE_SECONDS = 0.5
INPUT_ACTIONS = ("click", "clickState", "clickTarget", "type", "key", "drag")


def parse_args():
    parser = argparse.ArgumentParser(description="Solve a desktop task without a model, inside the gui image.")
    parser.add_argument("--task", action="append", required=True)
    parser.add_argument("--settle", action="store_true", help="measure the settle time of every input action")
    parser.add_argument("--dump", action="store_true", help="print the accessibility tree after the launch")
    return parser.parse_args()


def environment():
    variables = dict(os.environ)
    variables.update({"DISPLAY": DISPLAY, "XDG_RUNTIME_DIR": str(RUNTIME), "LC_ALL": "C.UTF-8"})
    return variables


class Display:
    def __init__(self, width, height):
        RUNTIME.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.env = environment()
        self.server = subprocess.Popen(
            ["Xvfb", DISPLAY, "-screen", "0", f"{width}x{height}x24", "-nolisten", "tcp"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.until(lambda: subprocess.run(["xdpyinfo", "-display", DISPLAY], capture_output=True).returncode == 0)
        bus = subprocess.run(["dbus-daemon", "--session", "--fork", "--print-address=1", "--nopidfile"],
                             env=self.env, capture_output=True, text=True, check=True)
        self.env["DBUS_SESSION_BUS_ADDRESS"] = bus.stdout.split()[0]
        self.window_manager = subprocess.Popen(["openbox"], env=self.env, stdout=subprocess.DEVNULL,
                                               stderr=subprocess.DEVNULL)
        self.until(lambda: "_NET_CLIENT_LIST(WINDOW)" in self.output(["xprop", "-root", "_NET_CLIENT_LIST"]))
        subprocess.run(["dbus-send", "--session", "--print-reply", "--dest=org.a11y.Bus", "/org/a11y/bus",
                        "org.freedesktop.DBus.Properties.Set", "string:org.a11y.Status", "string:IsEnabled",
                        "variant:boolean:true"], env=self.env, capture_output=True)
        self.width, self.height = width, height
        self.processes = []

    @staticmethod
    def until(condition, timeout=15, interval=0.1):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if condition():
                return True
            time.sleep(interval)
        return False

    def output(self, command):
        result = subprocess.run(command, env=self.env, capture_output=True, text=True)
        return result.stdout if result.returncode == 0 else ""

    def run(self, command):
        subprocess.run(command, env=self.env, check=True, capture_output=True)

    def launch(self, command, ready):
        log = RUNTIME / f"app-{len(self.processes)}.log"
        variables = dict(self.env)
        if Path(JAVA_ATK_WRAPPER).is_file():
            variables["JDK_JAVA_OPTIONS"] = (f"-Xbootclasspath/a:{JAVA_ATK_WRAPPER} "
                                            "-Djavax.accessibility.assistive_technologies="
                                            "org.GNOME.Accessibility.AtkWrapper")
        with open(log, "w") as output:
            process = subprocess.Popen(["bash", "-c", command], cwd="/workspace", env=variables, stdout=output,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        self.processes.append(process)
        if ready:
            if not self.until(lambda: ready in log.read_text(errors="replace"), timeout=60):
                raise RuntimeError(f"{command} did not print {ready!r}: {log.read_text(errors='replace')[-800:]}")
        return log

    def window_title(self, prefix):
        found = self.output(["xdotool", "search", "--name", f"^{prefix} "]).split()
        for window in found:
            title = self.output(["xdotool", "getwindowname", window]).strip()
            if title.startswith(prefix + " {"):
                return title
        return None

    def tree(self):
        arguments = {"screenWidth": self.width, "screenHeight": self.height, "maxNodes": 400, "maxDepth": 40,
                     "scope": "all"}
        text = self.output(["python3", str(READER), "tree", json.dumps(arguments)])
        return json.loads(text)["nodes"] if text else []

    def close(self):
        for process in self.processes:
            try:
                os.killpg(process.pid, 9)
            except ProcessLookupError:
                pass
        self.window_manager.kill()
        self.server.kill()


class Screen:
    def __init__(self):
        import gi

        gi.require_version("Gdk", "3.0")
        from gi.repository import Gdk

        self.gdk = Gdk
        self.root = Gdk.get_default_root_window()

    def tiles(self):
        pixbuf = self.gdk.pixbuf_get_from_window(self.root, 0, 0, self.root.get_width(), self.root.get_height())
        pixels = pixbuf.get_pixels()
        stride = pixbuf.get_rowstride()
        channels = pixbuf.get_n_channels()
        width, height = pixbuf.get_width(), pixbuf.get_height()
        hashes = {}
        for tile_y in range(0, height, TILE):
            for tile_x in range(0, width, TILE):
                span = min(TILE, width - tile_x) * channels
                value = 0
                for row in range(tile_y, min(tile_y + TILE, height)):
                    start = row * stride + tile_x * channels
                    value = zlib.crc32(pixels[start:start + span], value)
                hashes[(tile_x, tile_y)] = value
        return hashes

    def noisy_tiles(self):
        noisy = set()
        previous = self.tiles()
        deadline = time.time() + IDLE_SECONDS
        while time.time() < deadline:
            current = self.tiles()
            noisy.update(key for key, value in current.items() if previous.get(key) != value)
            previous = current
        return noisy, previous

    def settle(self, noisy, before, acted_at):
        previous = before
        history = {key: [value] for key, value in before.items() if key not in noisy}
        changes = {}
        frames = 0
        while time.time() - acted_at < SETTLE_WINDOW_SECONDS:
            current = self.tiles()
            now = time.time() - acted_at
            frames += 1
            for key, value in current.items():
                if key in noisy or previous.get(key) == value:
                    continue
                changes.setdefault(key, []).append(now)
                history[key].append(value)
            previous = current
        last_change = None
        blinking = 0
        late = []
        for key, times in changes.items():
            values = history[key]
            toggles = len(values) != len(set(values))
            if toggles:
                blinking += 1
            counted = times[0] if toggles else times[-1]
            if counted > LATE_SECONDS:
                late.append({"x": key[0], "y": key[1], "ms": round(counted * 1000), "changes": len(times)})
            last_change = counted if last_change is None else max(last_change, counted)
        return {"settledMs": None if last_change is None else round(last_change * 1000),
                "changedTiles": len(changes), "blinkingTiles": blinking, "lateTiles": late,
                "frameMs": round(SETTLE_WINDOW_SECONDS * 1000 / max(frames, 1))}


def cgroup(name):
    try:
        return int(Path("/sys/fs/cgroup", name).read_text().split()[0])
    except (OSError, ValueError, IndexError):
        return None


def find_node(display, query, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for node in display.tree():
            if query["name"] not in (node.get("name"), node.get("text")):
                continue
            if query.get("role") and node.get("role") != query["role"]:
                continue
            if node.get("width", 0) > 0 and node.get("height", 0) > 0:
                return node
        time.sleep(0.3)
    raise RuntimeError(f"No element {query} in the tree")


def center(box, fraction_y=0.0):
    return (box["x"] + box["width"] // 2, int(box["y"] + box["height"] / 2 + fraction_y * box["height"]))


def read_state(display, app):
    kind, where = app["state"]
    if kind == "file":
        try:
            return json.loads(Path(where).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
    title = display.window_title(where)
    return json.loads(title[len(where) + 1:]) if title else None


def click_at(display, x, y):
    display.run(["xdotool", "mousemove", str(x), str(y), "click", "1"])


def perform(display, app, step):
    action = step[0]
    if action == "click":
        x, y = center(find_node(display, step[1]))
        click_at(display, x, y)
    elif action == "clickState":
        x, y = center(read_state(display, app)[step[1]])
        click_at(display, x, y)
    elif action == "clickTarget":
        target = next(item for item in read_state(display, app)["targets"] if item["label"] == step[1])
        x, y = center(target)
        click_at(display, x, y)
    elif action == "type":
        display.run(["xdotool", "type", "--delay", "20", step[1]])
    elif action == "key":
        display.run(["xdotool", "key", step[1]])
    elif action == "drag":
        start = center(find_node(display, step[1]))
        end = center(find_node(display, step[2]), step[3])
        display.run(["xdotool", "mousemove", str(start[0]), str(start[1]), "mousedown", "1"])
        for index in range(1, 11):
            x = start[0] + (end[0] - start[0]) * index // 10
            y = start[1] + (end[1] - start[1]) * index // 10
            display.run(["xdotool", "mousemove", str(x), str(y)])
            time.sleep(0.02)
        display.run(["xdotool", "mouseup", "1"])
    elif action == "waitState":
        if not display.until(lambda: (read_state(display, app) or {}).get(step[1]), timeout=10):
            raise RuntimeError(f"The state never had {step[1]}")
    elif action == "waitTree":
        find_node(display, step[1])
    else:
        raise ValueError(action)


def solve(task, settle, dump):
    app = catalog.APPS[task["app"]]
    width, height = task.get("size", (1280, 800))
    Path(catalog.STATE_DIR).mkdir(parents=True, exist_ok=True)
    for stale in Path(catalog.STATE_DIR).glob("*"):
        stale.unlink()
    if task.get("setup"):
        subprocess.run(["bash", "-c", task["setup"]], check=True)
    display = Display(width, height)
    screen = Screen() if settle else None
    timings = []
    started = time.time()
    try:
        display.launch(catalog.launch_command(task), app["ready"])
        window = app.get("window")
        if window:
            display.until(lambda: display.window_title(window) is not None, timeout=60)
        display.until(lambda: read_state(display, app) is not None, timeout=30)
        display.until(lambda: "0x" in display.output(["xprop", "-root", "_NET_CLIENT_LIST"]), timeout=30)
        time.sleep(1.0)
        resources = {"memoryMb": round((cgroup("memory.current") or 0) / 2**20), "pids": cgroup("pids.current")}
        if dump:
            for node in display.tree():
                print(json.dumps(node, ensure_ascii=False), file=sys.stderr)
        for step in task["reference"]:
            if screen and step[0] in INPUT_ACTIONS:
                noisy, before = screen.noisy_tiles()
                perform(display, app, step)
                timing = screen.settle(noisy, before, time.time())
                timings.append({"action": step[0], **timing})
            else:
                perform(display, app, step)
                time.sleep(0.15)
        display.until(lambda: task["check"](read_state(display, app) or {}), timeout=5)
        state = read_state(display, app)
        return {"task": task["id"], "app": task["app"], "toolkit": app["toolkit"], "kind": task["kind"],
                "passed": bool(state and task["check"](state)), "state": state, "settle": timings,
                "resources": {**resources, "pidsPeak": cgroup("pids.peak")},
                "seconds": round(time.time() - started, 1)}
    except Exception as error:
        return {"task": task["id"], "app": task["app"], "toolkit": app["toolkit"], "kind": task["kind"],
                "passed": False, "error": str(error), "state": read_state(display, app), "settle": timings}
    finally:
        display.close()
        time.sleep(0.5)


def main():
    args = parse_args()
    for task_id in args.task:
        print(json.dumps(solve(catalog.find_task(task_id), args.settle, args.dump), ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
