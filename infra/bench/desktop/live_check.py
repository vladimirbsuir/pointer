import argparse
import datetime
import importlib.util
import json
import os
import shutil
import subprocess
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROUTING_SPEC = importlib.util.spec_from_file_location("routing_bench", HERE.parent / "routing" / "run.py")
routing = importlib.util.module_from_spec(ROUTING_SPEC)
ROUTING_SPEC.loader.exec_module(routing)
Api = routing.Api

PROJECT_NAME = "desktop-fixture"
STATE_PATH = "/tmp/tk-state.json"
NOTES_STATE_PATH = "/tmp/gtk-notes-state.json"
LAUNCH = (
    "Test the Tk application in app.py with the desktop tools. Start the display, launch it with "
    f"`python3 app.py {STATE_PATH}`, wait for its window, "
)


def counted(count):
    return lambda check: bool(check and check.get("count") == count and check.get("text") == "hello")


TASKS = {
    "counter": {
        "app": "tk-counter",
        "state": STATE_PATH,
        "prompt": LAUNCH + "click the Increment button twice, type hello into the text field, and then tell me "
                           "the count shown in the window.",
        "success": counted(2),
        "checkKeys": ("count", "text"),
        "followUp": True,
    },
    "clicks": {
        "app": "tk-counter",
        "state": STATE_PATH,
        "prompt": LAUNCH + "then click the Increment button six times with one desktop_click call per click (do not "
                           "use the clicks argument) and check the count in the screenshot after every click. Then "
                           "type hello into the text field and tell me the final count shown in the window.",
        "success": counted(6),
        "checkKeys": ("count", "text"),
        "followUp": False,
    },
    "notes": {
        "app": "gtk-notes",
        "state": NOTES_STATE_PATH,
        "prompt": "Test the GTK application in app.py with the desktop tools. Start the display, launch it with "
                  f"`python3 app.py {NOTES_STATE_PATH}`, wait for its window, then add two notes, milk and bread, "
                  "check the Pinned box, open File > Export… and cancel the dialog. Tell me which notes the list "
                  "shows at the end.",
        "success": lambda check: bool(check and check.get("notes") == ["milk", "bread"] and check.get("pinned")
                                      and check.get("dialog") is False and check.get("exported") == 0),
        "checkKeys": ("notes", "pinned", "dialog", "exported"),
        "followUp": False,
    },
}
FOLLOW_UP = "Also tell me the title of the window."
SCHEMA_PROMPT = "Reply with the single word OK."


def parse_args():
    parser = argparse.ArgumentParser(description="Live check of the F7 desktop tools with real models.")
    parser.add_argument("--api", default=os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--email", default=os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--model", action="append", required=True,
                        help="model key, or provider::key when the key is ambiguous; repeat for several models")
    parser.add_argument("--coordinates", choices=("pixels", "normalized"), help="set the models' convention first")
    parser.add_argument("--image", default="pointer-runner:gui")
    parser.add_argument("--device-target", default="pointer-bench-device-target",
                        help="Docker volume with the bench device built inside the image (see README)")
    parser.add_argument("--backend-host", default="http://host.docker.internal:8080")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--task", choices=sorted(TASKS), default="counter",
                        help="counter: the message-order check; clicks: one screenshot per click, for image masking; "
                             "notes: a GTK task where the accessibility tree is available")
    parser.add_argument("--runs", type=int, default=1, help="runs of the task per model")
    parser.add_argument("--label", help="saved with the runs, e.g. the backend's image masking setting")
    parser.add_argument("--schema-cost", action="store_true",
                        help="also compare the prompt tokens of a one-step turn with and without the desktop tools")
    parser.add_argument("--schema-only", action="store_true", help="measure the schema cost without the GUI task")
    parser.add_argument("--out", default=str(HERE / "results"))
    args = parser.parse_args()
    if not (args.email and args.password):
        parser.error("set --email and --password (or POINTER_EMAIL and POINTER_PASSWORD)")
    return args


def find_model(api, spec):
    provider_name, _, key = spec.rpartition("::")
    matches = [
        (provider, model)
        for provider in api.call("GET", "/providers")
        if not provider_name or provider["name"] == provider_name
        for model in provider["models"]
        if model["modelKey"] == key and model["enabled"]
    ]
    if len(matches) != 1:
        raise SystemExit(f"Model {spec} matched {len(matches)} models; pass provider::key")
    return matches[0]


def fixture_project(api):
    for project in api.call("GET", "/projects"):
        if project["name"] == PROJECT_NAME:
            return project
    return api.call("POST", "/projects", {"name": PROJECT_NAME})


def prepare_workspace(out, app):
    workspace = out / "workspace"
    if workspace.exists():
        shutil.rmtree(workspace)
    workspace.mkdir(parents=True)
    shutil.copy(HERE / "apps" / app / "app.py", workspace / "app.py")
    return workspace


def start_device(args, api_email, password, device_id, project_id, workspace):
    name = f"pointer-bench-desktop-{device_id[:8]}"
    subprocess.run(
        [
            "docker", "run", "-d", "--rm", "--init", "--shm-size", "512m", "--name", name,
            "--add-host", "host.docker.internal:host-gateway",
            "-v", f"{workspace}:/workspace", "-v", f"{args.device_target}:/target",
            "-e", f"POINTER_API_URL={args.backend_host}", "-e", f"POINTER_EMAIL={api_email}",
            "-e", f"POINTER_PASSWORD={password}", "-e", f"POINTER_DEVICE_ID={device_id}",
            "-e", f"POINTER_PROJECT_ID={project_id}", "-e", "POINTER_WORKSPACE=/workspace",
            "--entrypoint", "/target/release/pointer-bench-device", args.image,
        ],
        check=True, capture_output=True, text=True,
    )
    deadline = time.time() + 60
    while time.time() < deadline:
        logs = subprocess.run(["docker", "logs", name], capture_output=True, text=True)
        if "connected" in logs.stdout + logs.stderr:
            return name
        time.sleep(1)
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    raise SystemExit("The bench device did not connect")


def container_file(name, path):
    result = subprocess.run(["docker", "exec", name, "cat", path], capture_output=True, text=True)
    return json.loads(result.stdout) if result.returncode == 0 and result.stdout.strip() else None


def items_of(api, session_id):
    return api.call("GET", f"/sessions/{session_id}/items")


def wait_for(api, session_id, timeout, condition):
    deadline = time.time() + timeout
    while time.time() < deadline:
        session = api.call("GET", f"/sessions/{session_id}")
        items = items_of(api, session_id)
        if condition(session, items):
            return session, items
        time.sleep(2)
    raise SystemExit(f"Session {session_id} did not reach the expected state in {timeout}s")


def has_screenshot(items):
    return any(item["type"] == "tool_call" and item.get("images") for item in items)


def sequences(items):
    top = [item for item in items if not item.get("parentItemId")]
    model_output = ("assistant_message", "tool_call")
    screenshot_step = False
    tool_user_assistant = False
    tool_user_user = False
    for index, item in enumerate(top):
        if item["type"] != "tool_call" or not item.get("images"):
            continue
        screenshot_step = True
        later = [other for other in top[index + 1:]
                 if other["type"] != "reasoning"
                 and not (other["type"] == "tool_call" and other.get("step") == item.get("step"))]
        if not later:
            continue
        if later[0]["type"] in model_output:
            tool_user_assistant = True
        elif later[0]["type"] == "user_message" and any(other["type"] in model_output for other in later[1:]):
            tool_user_user = True
    return screenshot_step, tool_user_assistant, tool_user_user


def run_gui_task(api, project, model, args, device):
    task = TASKS[args.task]
    subprocess.run(["docker", "exec", device, "rm", "-f", task["state"]], capture_output=True)
    session = api.call("POST", f"/projects/{project['id']}/sessions",
                       {"title": f"Desktop live check {model['modelKey']}", "mode": "agent",
                        "modelId": model["id"], "routing": "off", "escalation": "off"})
    session_id = session["id"]
    api.call("PATCH", f"/sessions/{session_id}", {"autoApprove": True})
    started = time.time()
    api.call("POST", f"/sessions/{session_id}/messages", {"text": task["prompt"], "mode": "agent"})
    follow_up_sent = False
    try:
        wait_for(api, session_id, args.timeout,
                 lambda current, items: has_screenshot(items) or current["status"] in ("idle", "error"))
        if task["followUp"] and api.call("GET", f"/sessions/{session_id}")["status"] not in ("idle", "error"):
            api.call("POST", f"/sessions/{session_id}/messages", {"text": FOLLOW_UP, "mode": "agent"})
            follow_up_sent = True
        state, items = wait_for(api, session_id, args.timeout,
                                lambda current, _items: current["status"] in ("idle", "error"))
    except SystemExit as timeout:
        api.call("POST", f"/sessions/{session_id}/cancel")
        state, items = api.call("GET", f"/sessions/{session_id}"), items_of(api, session_id)
        state["timeout"] = str(timeout)
    seconds = time.time() - started
    calls = [item for item in items if item["type"] == "tool_call" and not item.get("parentItemId")]
    answer = next((item.get("text") or "" for item in reversed(items)
                   if item["type"] == "assistant_message"), "")
    check = container_file(device, task["state"])
    screenshot_step, tool_user_assistant, tool_user_user = sequences(items)
    usage = api.call("GET", f"/sessions/{session_id}/usage")
    errors = [item.get("error") for item in items if item["type"] == "tool_call" and item.get("error")]
    return {
        "model": model["modelKey"],
        "task": args.task,
        "label": args.label,
        "sessionId": session_id,
        "status": state["status"],
        "seconds": round(seconds, 1),
        "toolCalls": [
            {"tool": call["toolName"], "status": call.get("toolStatus"), "arguments": call.get("arguments"),
             "marks": call.get("marks"), "images": len(call.get("images") or []),
             "error": (call.get("error") or {}).get("code")}
            for call in calls
        ],
        "followUpSent": follow_up_sent,
        "screenshotStep": screenshot_step,
        "acceptedToolUserAssistant": tool_user_assistant,
        "acceptedToolUserUser": tool_user_user if follow_up_sent else None,
        "check": {key: check.get(key) for key in task["checkKeys"]} if check else None,
        "screenshots": sum(len(call.get("images") or []) for call in calls),
        "success": task["success"](check),
        "elementClicks": sum(1 for call in calls if call["toolName"] == "desktop_click"
                             and (call.get("arguments") or {}).get("elementId")),
        "treeCalls": sum(1 for call in calls if call["toolName"] == "desktop_tree"),
        "answer": answer[:600],
        "toolErrors": errors,
        "usage": usage["entries"],
        "timeout": state.get("timeout"),
    }


def one_step_prompt_tokens(api, project, model):
    session = api.call("POST", f"/projects/{project['id']}/sessions",
                       {"title": "Desktop schema cost", "mode": "agent", "modelId": model["id"],
                        "routing": "off", "escalation": "off"})
    api.call("POST", f"/sessions/{session['id']}/messages", {"text": SCHEMA_PROMPT, "mode": "agent"})
    wait_for(api, session["id"], 300, lambda current, _items: current["status"] in ("idle", "error"))
    steps = [entry for entry in api.call("GET", f"/sessions/{session['id']}/usage")["entries"]
             if entry["purpose"] == "step"]
    return sum(entry["promptTokens"] for entry in steps) if steps else None


def schema_cost(api, project, provider, model):
    path = f"/providers/{provider['id']}/models/{model['id']}"
    api.call("PATCH", path, {"supportsVision": False})
    without = one_step_prompt_tokens(api, project, model)
    api.call("PATCH", path, {"supportsVision": True})
    with_tools = one_step_prompt_tokens(api, project, model)
    difference = None if without is None or with_tools is None else with_tools - without
    return {"model": model["modelKey"], "coordinates": model.get("coordinateSystem"),
            "promptTokensWithout": without, "promptTokensWith": with_tools, "difference": difference}


def main():
    args = parse_args()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    device_id = str(uuid.uuid4())
    api = Api(args.api, args.email, args.password, device_id)
    project = fixture_project(api)
    workspace = prepare_workspace(out, TASKS[args.task]["app"])
    models, originals = [], []
    for spec in args.model:
        provider, model = find_model(api, spec)
        originals.append((provider, model))
        changes = {"supportsVision": True}
        if args.coordinates:
            changes["coordinateSystem"] = args.coordinates
        model = api.call("PATCH", f"/providers/{provider['id']}/models/{model['id']}", changes)
        models.append((provider, model))
    device = start_device(args, args.email, args.password, device_id, project["id"], workspace)
    runs, costs = [], []
    try:
        for provider, model in models:
            for _ in range(0 if args.schema_only else args.runs):
                run = run_gui_task(api, project, model, args, device)
                run["provider"] = provider["name"]
                runs.append(run)
                step = next((entry for entry in run["usage"] if entry["purpose"] == "step"), {})
                print(f"{provider['name']}/{model['modelKey']}: {run['status']} success={run['success']} "
                      f"calls={len(run['toolCalls'])} screenshots={run['screenshots']} "
                      f"trees={run['treeCalls']} elementClicks={run['elementClicks']} "
                      f"prompt={step.get('promptTokens')} cached={step.get('cachedPromptTokens')} "
                      f"tool>user>assistant={run['acceptedToolUserAssistant']} "
                      f"tool>user>user={run['acceptedToolUserUser']} {run['seconds']}s", flush=True)
            if args.schema_cost or args.schema_only:
                cost = schema_cost(api, project, provider, model)
                costs.append(cost)
                print(f"  schema cost: {cost['difference']} prompt tokens", flush=True)
    finally:
        subprocess.run(["docker", "rm", "-f", device], capture_output=True)
        for provider, model in originals:
            api.call("PATCH", f"/providers/{provider['id']}/models/{model['id']}",
                     {"supportsVision": model["supportsVision"], "coordinateSystem": model["coordinateSystem"]})
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = out / f"live-check-{stamp}.json"
    path.write_text(json.dumps({"runs": runs, "schemaCost": costs}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Saved {path}")


if __name__ == "__main__":
    main()
