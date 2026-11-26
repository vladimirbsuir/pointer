import importlib.util
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
STATE_DIR = "/tmp/state"
GRID_SIZES = {"1024x768": (1024, 768), "1280x800": (1280, 800), "1920x1080": (1920, 1080)}
GRID_PICKS = 6


def state_file(app):
    return f"{STATE_DIR}/{app}.json"


APPS = {
    "tk-counter": {
        "toolkit": "Tk",
        "title": "Tk counter application in tk-counter/app.py",
        "launch": f"python3 tk-counter/app.py {state_file('tk-counter')}",
        "ready": "tk-counter ready",
        "state": ("file", state_file("tk-counter")),
        "files": ["tk-counter/app.py"],
    },
    "gtk-notes": {
        "toolkit": "GTK",
        "title": "GTK notes application in gtk-notes/app.py",
        "launch": f"python3 gtk-notes/app.py {state_file('gtk-notes')}",
        "ready": "gtk-notes ready",
        "state": ("file", state_file("gtk-notes")),
        "files": ["gtk-notes/app.py"],
    },
    "form-web": {
        "toolkit": "Chromium",
        "title": "web page form-web/index.html in Chromium",
        "launch": "pointer-chromium --user-data-dir=/tmp/form-web-profile --app=file:///workspace/form-web/index.html",
        "ready": None,
        "window": "form-web",
        "state": ("title", "form-web"),
        "files": ["form-web/index.html"],
    },
    "electron-todo": {
        "toolkit": "Electron",
        "title": "Electron todo application in electron-todo",
        "launch": "electron-todo/node_modules/.bin/electron --force-renderer-accessibility electron-todo",
        "ready": "electron-todo ready",
        "state": ("title", "electron-todo"),
        "files": ["electron-todo/main.js", "electron-todo/index.html", "electron-todo/package.json"],
    },
    "tauri-counter": {
        "toolkit": "Tauri",
        "title": "Tauri counter application, already built as /tauri/release/tauri-counter",
        "launch": f"/tauri/release/tauri-counter {state_file('tauri-counter')}",
        "ready": "tauri-counter ready",
        "state": ("file", state_file("tauri-counter")),
        "files": [],
    },
    "qt-settings": {
        "toolkit": "Qt",
        "title": "Qt settings application in qt-settings/app.py",
        "launch": f"/qt/bin/python qt-settings/app.py {state_file('qt-settings')}",
        "ready": "qt-settings ready",
        "state": ("file", state_file("qt-settings")),
        "files": ["qt-settings/app.py"],
    },
    "swing-calc": {
        "toolkit": "Swing",
        "title": "Java Swing calculator in swing-calc/SwingCalc.java",
        "launch": f"java swing-calc/SwingCalc.java {state_file('swing-calc')}",
        "ready": "swing-calc ready",
        "state": ("file", state_file("swing-calc")),
        "files": ["swing-calc/SwingCalc.java"],
    },
    "grid-targets": {
        "toolkit": "Tk",
        "title": "click target screen in grid-targets/app.py",
        "launch": f"python3 grid-targets/app.py {state_file('grid-targets')}",
        "ready": "grid-targets ready",
        "state": ("file", state_file("grid-targets")),
        "files": ["grid-targets/app.py"],
    },
}


def tree(name, role=None):
    return {"name": name, "role": role}


def click(name, role=None):
    return ("click", tree(name, role))


def letters(value):
    return re.sub("[^a-z]", "", (value or "").lower())


TASKS = [
    {
        "id": "tk-single",
        "app": "tk-counter",
        "kind": "single",
        "goal": "click the Increment button once.",
        "question": "the count shown in the window",
        "check": lambda state: state.get("count") == 1,
        "reference": [("clickState", "button")],
    },
    {
        "id": "tk-sequence",
        "app": "tk-counter",
        "kind": "sequence",
        "goal": "click the Increment button three times, type hello into the text field and press Ctrl+S to save.",
        "question": "the count shown in the window",
        "check": lambda state: state.get("count") == 3 and state.get("text") == "hello" and state.get("saved", 0) >= 1,
        "reference": [("clickState", "button")] * 3 + [("clickState", "entry"), ("type", "hello"), ("key", "ctrl+s")],
    },
    {
        "id": "tk-trap",
        "app": "tk-counter",
        "kind": "trap",
        "arguments": "--milestone=3",
        "goal": "make the count shown in the window reach 5.",
        "question": "the final count",
        "check": lambda state: state.get("count") == 5 and not state.get("dialog"),
        "reference": [("clickState", "button")] * 3
        + [("waitState", "ok"), ("clickState", "ok")]
        + [("clickState", "button")] * 2,
    },
    {
        "id": "gtk-single",
        "app": "gtk-notes",
        "kind": "single",
        "goal": "check the Pinned box.",
        "question": "whether Pinned is checked",
        "check": lambda state: state.get("pinned") is True,
        "reference": [click("Pinned", "check box")],
    },
    {
        "id": "gtk-sequence",
        "app": "gtk-notes",
        "kind": "sequence",
        "goal": "add two notes, milk and bread, check the Pinned box, then open File > Export… and cancel the dialog.",
        "question": "which notes the list shows at the end",
        "check": lambda state: state.get("notes") == ["milk", "bread"] and state.get("pinned") is True
        and state.get("dialog") is False and state.get("exported") == 0,
        "reference": [
            click("Note text"), ("type", "milk"), click("Add note", "button"),
            click("Note text"), ("type", "bread"), click("Add note", "button"),
            click("Pinned", "check box"), click("File", "menu"), click("Export…", "menu item"),
            ("waitTree", tree("Cancel", "button")), click("Cancel", "button"),
        ],
    },
    {
        "id": "gtk-trap",
        "app": "gtk-notes",
        "kind": "trap",
        "setup": "printf 'old list\\n' > /tmp/shopping.txt",
        "goal": "add the note milk and export the notes to the file /tmp/shopping.txt.",
        "question": "where the notes were exported",
        "check": lambda state: (state.get("lastExport") or {}).get("path") == "/tmp/shopping.txt"
        and (state.get("lastExport") or {}).get("text") == "milk\n" and state.get("dialog") is False,
        "reference": [
            click("Note text"), ("type", "milk"), click("Add note", "button"),
            click("File", "menu"), click("Export…", "menu item"),
            ("waitTree", tree("Export", "button")), ("key", "ctrl+a"), ("type", "/tmp/shopping.txt"),
            ("key", "Return"), ("waitTree", tree("Replace", "button")), click("Replace", "button"),
        ],
    },
    {
        "id": "web-single",
        "app": "form-web",
        "kind": "single",
        "goal": "drag the magenta list item D to the top of the coloured list, so that it comes before A.",
        "question": "the order of the list",
        "check": lambda state: state.get("order") == "DABC",
        "reference": [("drag", tree("D", "list item"), tree("A", "list item"), -0.3)],
    },
    {
        "id": "web-sequence",
        "app": "form-web",
        "kind": "sequence",
        "goal": "register the user ada with the email ada@example.com using the form, then close the confirmation dialog.",
        "question": "what the confirmation said",
        "check": lambda state: state.get("users") == ["ada"] and state.get("dialog") is False,
        "reference": [
            click("Username", "entry"), ("type", "ada"), click("Email", "entry"), ("type", "ada@example.com"),
            click("Register", "button"), ("waitTree", tree("Close", "button")),
            click("Close", "button"),
        ],
    },
    {
        "id": "web-trap",
        "app": "form-web",
        "kind": "trap",
        "goal": "register the user Ada Lovelace with the email ada@example.com using the form, then close the "
                "confirmation dialog.",
        "question": "the username that was registered",
        "check": lambda state: len(state.get("users") or []) == 1 and letters(state["users"][0]) == "adalovelace"
        and state.get("dialog") is False,
        "reference": [
            click("Username", "entry"), ("type", "Ada Lovelace"), click("Email", "entry"),
            ("type", "ada@example.com"), click("Register", "button"), click("Username", "entry"),
            ("key", "ctrl+a"), ("type", "Ada-Lovelace"), click("Register", "button"),
            ("waitTree", tree("Close", "button")), click("Close", "button"),
        ],
    },
    {
        "id": "electron-single",
        "app": "electron-todo",
        "kind": "single",
        "goal": "add the todo milk.",
        "question": "which todos the list shows",
        "check": lambda state: state.get("todos") == ["milk"],
        "reference": [click("New todo", "entry"), ("type", "milk"), click("Add", "button")],
    },
    {
        "id": "electron-sequence",
        "app": "electron-todo",
        "kind": "sequence",
        "goal": "add three todos, milk, bread and eggs, mark bread as done and turn on Hide done.",
        "question": "which todos are still visible",
        "check": lambda state: state.get("todos") == ["milk", "bread", "eggs"] and state.get("done") == ["bread"]
        and state.get("hideDone") is True,
        "reference": [
            click("New todo", "entry"), ("type", "milk"), ("key", "Return"),
            ("type", "bread"), ("key", "Return"), ("type", "eggs"), ("key", "Return"),
            click("bread", "check box"), click("Hide done", "check box"),
        ],
    },
    {
        "id": "electron-trap",
        "app": "electron-todo",
        "kind": "trap",
        "arguments": "--whats-new",
        "goal": "add two todos, milk and bread.",
        "question": "which todos the list shows",
        "check": lambda state: state.get("todos") == ["milk", "bread"] and state.get("overlay") is False,
        "reference": [
            ("waitTree", tree("Got it", "button")), click("Got it", "button"),
            click("New todo", "entry"), ("type", "milk"), click("Add", "button"),
            click("New todo", "entry"), ("type", "bread"), click("Add", "button"),
        ],
    },
    {
        "id": "tauri-single",
        "app": "tauri-counter",
        "kind": "single",
        "goal": "click the Increment button once.",
        "question": "the count shown in the window",
        "check": lambda state: state.get("count") == 1,
        "reference": [click("Increment", "button")],
    },
    {
        "id": "tauri-sequence",
        "app": "tauri-counter",
        "kind": "sequence",
        "goal": "type Release into the counter name field, click Save name, then click Increment twice.",
        "question": "the heading and the count shown in the window",
        "check": lambda state: state.get("name") == "Release" and state.get("count") == 2,
        "reference": [
            click("Counter name", "entry"), ("type", "Release"), click("Save name", "button"),
            click("Increment", "button"), click("Increment", "button"),
        ],
    },
    {
        "id": "tauri-trap",
        "app": "tauri-counter",
        "kind": "trap",
        "arguments": "--lock-at=3",
        "goal": "make the count shown in the window reach 5.",
        "question": "the final count",
        "check": lambda state: state.get("count") == 5 and state.get("dialog") is False,
        "reference": [click("Increment", "button")] * 3
        + [("waitTree", tree("Unlock", "button")), click("Unlock", "button")]
        + [click("Increment", "button")] * 2,
    },
    {
        "id": "qt-sequence",
        "app": "qt-settings",
        "kind": "sequence",
        "goal": "check Dark mode and set Font size to 16 on the General tab, set Proxy to proxy.local:3128 on the "
                "Advanced tab, then click Apply.",
        "question": "the settings that were applied",
        "check": lambda state: state.get("applied", 0) >= 1 and state.get("dark") is True
        and state.get("fontSize") == 16 and state.get("proxy") == "proxy.local:3128",
        "reference": [
            click("Dark mode", "check box"), click("Font size", "spin button"), ("key", "ctrl+a"), ("type", "16"),
            click("Advanced", "page tab"), ("waitTree", tree("Proxy")), click("Proxy"), ("type", "proxy.local:3128"),
            click("Apply", "button"),
        ],
    },
    {
        "id": "swing-sequence",
        "app": "swing-calc",
        "kind": "sequence",
        "goal": "use it to add 12 and 30: replace the first number with 12 and the second with 30, then click Plus.",
        "question": "the result shown in the window",
        "check": lambda state: state.get("result") == "42",
        "reference": [
            click("First number"), ("key", "ctrl+a"), ("type", "12"), click("Second number"), ("key", "ctrl+a"),
            ("type", "30"), click("Plus", "button"),
        ],
    },
]

MATRIX = [task["id"] for task in TASKS if task["app"] in ("tk-counter", "gtk-notes", "form-web", "electron-todo",
                                                         "tauri-counter")]
TREE_ABLATION = ["gtk-sequence", "qt-sequence", "web-sequence", "electron-sequence", "tauri-sequence",
                 "swing-sequence", "tk-sequence"]
MASKING = ["gtk-sequence", "electron-sequence"]


def grid_module():
    spec = importlib.util.spec_from_file_location("grid_targets", HERE / "apps" / "grid-targets" / "app.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def grid_task(size, seed, dense=False):
    width, height = GRID_SIZES[size]
    picks = grid_module().picks(seed, GRID_PICKS)
    arguments = f"--seed={seed} --dense" if dense else f"--seed={seed}"
    return {
        "id": f"grid{'dense' if dense else ''}-{size}-{seed}",
        "app": "grid-targets",
        "kind": "grounding",
        "size": (width, height),
        "seed": seed,
        "picks": picks,
        "arguments": arguments,
        "prompt": (
            "This is a click accuracy test of the desktop tools. Start the display at "
            f"{width}x{height} with desktop_start, launch the {APPS['grid-targets']['title']} with "
            f"`{APPS['grid-targets']['launch']} {arguments}` and wait for its window. It shows many labelled "
            f"buttons. Take one screenshot, then click these buttons in this order, one desktop_click per button "
            f"with observe set to false: {', '.join(picks)}. Click each button exactly once, even if you are "
            "unsure, and do not retry or take other screenshots between the clicks. Then take one screenshot "
            "and tell me which of the buttons turned green."
        ),
        "check": lambda state: grid_hits(state, picks) == len(picks),
        "reference": [("clickTarget", label) for label in picks],
    }


def grid_hits(state, picks):
    clicks = (state or {}).get("clicks") or []
    return sum(1 for index, label in enumerate(picks) if index < len(clicks) and clicks[index]["label"] == label)


def find_task(task_id):
    if task_id.startswith("grid"):
        kind, size, seed = task_id.split("-")
        return grid_task(size, int(seed), kind == "griddense")
    for task in TASKS:
        if task["id"] == task_id:
            return task
    raise KeyError(task_id)


def launch_command(task):
    app = APPS[task["app"]]
    arguments = task.get("arguments")
    command = app["launch"]
    return f"{command} {arguments}" if arguments else command


def prompt(task):
    if "prompt" in task:
        return task["prompt"]
    app = APPS[task["app"]]
    return (
        f"Test the {app['title']} with the desktop tools. Start the display, launch it with "
        f"`{launch_command(task)}` and wait for its window. Then {task['goal']} Work only through the desktop "
        f"tools, not by editing files. When you are done, tell me {task['question']}."
    )
