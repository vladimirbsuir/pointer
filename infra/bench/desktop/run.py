import argparse
import datetime
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE))
import tasks as catalog

ROUTING_SPEC = importlib.util.spec_from_file_location("routing_bench", HERE.parent / "routing" / "run.py")
routing = importlib.util.module_from_spec(ROUTING_SPEC)
ROUTING_SPEC.loader.exec_module(routing)
Api = routing.Api

PROJECT_NAME = "desktop-bench"
POSTGRES = "pointer-postgres-1"
INPUT_TOOLS = ("desktop_click", "desktop_move", "desktop_drag", "desktop_scroll", "desktop_type", "desktop_key")
GROUPS = {
    "matrix": catalog.MATRIX,
    "tree": catalog.TREE_ABLATION,
    "masking": catalog.MASKING,
    "all": [task["id"] for task in catalog.TASKS],
}
RESUME = "The provider returned an error. Continue the task."
CALIBRATION_SIZES = [(1280, 800), (1280, 720), (1024, 768), (640, 400)]


def parse_args():
    parser = argparse.ArgumentParser(description="F7 benchmark: GUI tasks per model on the bench device in the gui image.")
    parser.add_argument("--api", default=os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--email", default=os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--model", action="append", default=[], help="key or provider::key; repeat for several")
    parser.add_argument("--tasks", default="matrix",
                        help="comma-separated task ids or groups (matrix, tree, masking, all); "
                             "grid-<size>-<seed> for click accuracy, e.g. grid-1280x800-1")
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--label", default="", help="saved with every run, e.g. the masking policy")
    parser.add_argument("--coordinates", choices=("pixels", "normalized"), help="set the models' convention first")
    parser.add_argument("--no-tree", action="store_true", help="hide desktop_tree for the runs (local database)")
    parser.add_argument("--image", default="pointer-runner:gui")
    parser.add_argument("--device-target", default="pointer-bench-device-target")
    parser.add_argument("--backend-host", default="http://host.docker.internal:8080")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--pause", type=int, default=0, help="seconds between runs, for rate limits")
    parser.add_argument("--retries", type=int, default=1, help="reruns of a run that ended in a provider error")
    parser.add_argument("--retry-wait", type=int, default=65)
    parser.add_argument("--resumes", type=int, default=0,
                        help="after a provider error, wait --retry-wait seconds and ask the session to continue, "
                             "up to this many times, instead of starting the run again")
    parser.add_argument("--settle-ms", type=int, default=300, help="the backend's pointer.desktop.settle-ms")
    parser.add_argument("--verify-fixture", action="store_true", help="solve the tasks without a model")
    parser.add_argument("--settle", action="store_true", help="with --verify-fixture: measure settle times")
    parser.add_argument("--calibrate", action="store_true", help="prompt tokens of one image per size and model")
    parser.add_argument("--out", default=str(HERE / "results"))
    args = parser.parse_args()
    if not args.verify_fixture:
        if not (args.email and args.password):
            parser.error("set --email and --password (or POINTER_EMAIL and POINTER_PASSWORD)")
        if not args.model:
            parser.error("give at least one --model")
    return args


def selected_tasks(spec):
    selected = []
    for part in filter(None, (value.strip() for value in spec.split(","))):
        selected.extend(GROUPS.get(part, [part]))
    for task_id in selected:
        catalog.find_task(task_id)
    return selected


def prepare_workspace(out):
    workspace = out / f"workspace-{os.getpid()}"
    if workspace.exists():
        shutil.rmtree(workspace)
    for app in catalog.APPS.values():
        for relative in app["files"]:
            target = workspace / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(HERE / "apps" / relative, target)
    (workspace / "electron-todo" / "node_modules").mkdir(parents=True, exist_ok=True)
    return workspace


def mounts(workspace):
    return [
        "-v", f"{workspace}:/workspace",
        "-v", "pointer-electron-todo-modules:/workspace/electron-todo/node_modules",
        "-v", "pointer-qt-venv:/qt",
        "-v", "pointer-tauri-target:/tauri",
        "-v", f"{REPO}:/repo:ro",
    ]


def docker(arguments, **options):
    environment = dict(os.environ, MSYS_NO_PATHCONV="1")
    return subprocess.run(["docker", *arguments], env=environment, **options)


def stamp():
    return datetime.datetime.now().strftime("%Y%m%d-%H%M%S")


def save(path, payload):
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def verify_fixture(args, out):
    workspace = prepare_workspace(out)
    results = []
    for task_id in selected_tasks(args.tasks):
        command = ["run", "--rm", "--init", "--shm-size", "512m", *mounts(workspace), "--entrypoint", "python3",
                   args.image, "/repo/infra/bench/desktop/reference.py", "--task", task_id]
        if args.settle:
            command.append("--settle")
        completed = docker(command, capture_output=True, text=True, encoding="utf-8")
        lines = [line for line in completed.stdout.splitlines() if line.startswith("{")]
        result = json.loads(lines[-1]) if lines else {"task": task_id, "passed": False,
                                                      "error": completed.stderr[-1500:]}
        results.append(result)
        settled = [entry["settledMs"] for entry in result.get("settle", []) if entry["settledMs"] is not None]
        print(f"{task_id}: {'pass' if result['passed'] else 'FAIL'} {result.get('seconds', '')}s "
              f"settle={settled} {result.get('error', '')}", flush=True)
    path = out / f"reference-{stamp()}.json"
    save(path, {"results": results})
    print(f"Saved {path}")
    shutil.rmtree(workspace, ignore_errors=True)
    if not all(result["passed"] for result in results):
        raise SystemExit(1)


def find_model(api, spec):
    provider_name, _, key = spec.rpartition("::")
    matches = [(provider, model) for provider in api.call("GET", "/providers")
               if not provider_name or provider["name"] == provider_name
               for model in provider["models"] if model["modelKey"] == key and model["enabled"]]
    if len(matches) != 1:
        raise SystemExit(f"Model {spec} matched {len(matches)} models; pass provider::key")
    return matches[0]


def bench_project(api):
    for project in api.call("GET", "/projects"):
        if project["name"] == PROJECT_NAME:
            return project
    return api.call("POST", "/projects", {"name": PROJECT_NAME})


def set_tree_tool(enabled):
    value = "true" if enabled else "false"
    docker(["exec", POSTGRES, "psql", "-U", "pointer", "-d", "pointer", "-c",
            f"update tools set enabled = {value} where name = 'desktop_tree' and owner_user_id is null"],
           check=True, capture_output=True)


class Device:
    def __init__(self, args, device_id, project_id, workspace):
        self.args = args
        self.device_id = device_id
        self.project_id = project_id
        self.workspace = workspace
        self.name = f"pointer-bench-desktop-{device_id[:8]}"

    def start(self):
        self.stop()
        docker(["run", "-d", "--rm", "--init", "--shm-size", "512m", "--name", self.name,
                "--add-host", "host.docker.internal:host-gateway", *mounts(self.workspace),
                "-v", f"{self.args.device_target}:/target",
                "-e", f"POINTER_API_URL={self.args.backend_host}", "-e", f"POINTER_EMAIL={self.args.email}",
                "-e", f"POINTER_PASSWORD={self.args.password}", "-e", f"POINTER_DEVICE_ID={self.device_id}",
                "-e", f"POINTER_PROJECT_ID={self.project_id}", "-e", "POINTER_WORKSPACE=/workspace",
                "--entrypoint", "/target/release/pointer-bench-device", self.args.image],
               check=True, capture_output=True, text=True)
        deadline = time.time() + 60
        while time.time() < deadline:
            logs = docker(["logs", self.name], capture_output=True, text=True, encoding="utf-8")
            if "connected" in (logs.stdout or "") + (logs.stderr or ""):
                return
            time.sleep(1)
        raise SystemExit("The bench device did not connect")

    def stop(self):
        docker(["rm", "-f", self.name], capture_output=True)

    def shell(self, command):
        return docker(["exec", self.name, "bash", "-c", command], capture_output=True, text=True, encoding="utf-8")

    def state(self, app):
        kind, where = app["state"]
        if kind == "file":
            result = self.shell(f"cat {where}")
            text = result.stdout.strip()
        else:
            result = self.shell(
                f"for w in $(DISPLAY=:99 xdotool search --name '^{where} ' 2>/dev/null); do "
                f"DISPLAY=:99 xdotool getwindowname $w; done")
            titles = [line for line in result.stdout.splitlines() if line.startswith(where + " {")]
            text = titles[0][len(where) + 1:] if titles else ""
        try:
            return json.loads(text) if text else None
        except ValueError:
            return None


def parse_time(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00")) if value else None


def wait_idle(api, session_id, timeout, observe=None):
    deadline = time.time() + timeout
    while time.time() < deadline:
        session = api.call("GET", f"/sessions/{session_id}")
        if observe:
            observe()
        if session["status"] in ("idle", "error"):
            return session, False
        time.sleep(2)
    api.call("POST", f"/sessions/{session_id}/cancel")
    return api.call("GET", f"/sessions/{session_id}"), True


def call_summary(call):
    arguments = call.get("arguments") or {}
    started, completed = parse_time(call.get("createdAt")), parse_time(call.get("completedAt"))
    return {
        "tool": call["toolName"],
        "status": call.get("toolStatus") or call.get("status"),
        "arguments": arguments,
        "marks": call.get("marks"),
        "images": len(call.get("images") or []),
        "error": (call.get("error") or {}).get("code"),
        "seconds": round((completed - started).total_seconds(), 2) if started and completed else None,
    }


def repeated_actions(calls):
    repeats = 0
    previous = None
    for call in calls:
        signature = (call["tool"], json.dumps(call["arguments"], sort_keys=True))
        if call["tool"] in INPUT_TOOLS and signature == previous:
            repeats += 1
        previous = signature
    return repeats


def timing(calls, total, settle_ms):
    tool_seconds = sum(call["seconds"] or 0 for call in calls)
    wait_seconds = sum(call["seconds"] or 0 for call in calls if call["tool"] == "desktop_wait")
    observed_inputs = sum(1 for call in calls if call["tool"] in INPUT_TOOLS
                          and call["arguments"].get("observe", True) is not False)
    settle_seconds = observed_inputs * settle_ms / 1000
    return {
        "total": round(total, 1),
        "tools": round(tool_seconds, 1),
        "wait": round(wait_seconds, 1),
        "settle": round(settle_seconds, 1),
        "otherTools": round(tool_seconds - wait_seconds - settle_seconds, 1),
        "model": round(total - tool_seconds, 1),
    }


def grounding(task, state, calls):
    clicks = (state or {}).get("clicks") or []
    targets = {target["label"]: target for target in (state or {}).get("targets") or []}
    samples = []
    for index, label in enumerate(task["picks"]):
        if index >= len(clicks):
            samples.append({"label": label, "hit": False, "missing": True})
            continue
        click = clicks[index]
        target = targets[label]
        center_x, center_y = target["x"] + target["width"] / 2, target["y"] + target["height"] / 2
        samples.append({
            "label": label,
            "hit": click["label"] == label,
            "clicked": click["label"],
            "dx": round(click["x"] - center_x, 1),
            "dy": round(click["y"] - center_y, 1),
            "width": target["width"],
            "height": target["height"],
        })
    return {"samples": samples, "hits": sum(1 for sample in samples if sample["hit"]),
            "clicks": len(clicks), "desktopClicks": sum(1 for call in calls if call["tool"] == "desktop_click")}


def run_task(api, project, model, task, args, device):
    app = catalog.APPS[task["app"]]
    device.start()
    device.shell(f"mkdir -p {catalog.STATE_DIR} && rm -f {catalog.STATE_DIR}/*")
    if task.get("setup"):
        device.shell(task["setup"])
    session = api.call("POST", f"/projects/{project['id']}/sessions",
                       {"title": f"Desktop bench {task['id']} {model['modelKey']}", "mode": "agent",
                        "modelId": model["id"], "routing": "off", "escalation": "off"})
    session_id = session["id"]
    api.call("PATCH", f"/sessions/{session_id}", {"autoApprove": True})
    observed = {}

    def observe():
        current = device.state(app)
        if current is not None:
            observed["state"] = current

    started = time.time()
    api.call("POST", f"/sessions/{session_id}/messages", {"text": catalog.prompt(task), "mode": "agent"})
    state_after, timed_out = wait_idle(api, session_id, args.timeout, observe)
    resumes = 0
    paused = 0.0
    while state_after["status"] == "error" and not timed_out and resumes < args.resumes:
        resumes += 1
        time.sleep(args.retry_wait)
        paused += args.retry_wait
        api.call("POST", f"/sessions/{session_id}/messages", {"text": RESUME, "mode": "agent"})
        state_after, timed_out = wait_idle(api, session_id, args.timeout, observe)
    seconds = time.time() - started - paused
    items = api.call("GET", f"/sessions/{session_id}/items")
    usage = api.call("GET", f"/sessions/{session_id}/usage")
    final_state = device.state(app)
    app_state = final_state if final_state is not None else observed.get("state")
    calls = [call_summary(item) for item in items if item["type"] == "tool_call" and not item.get("parentItemId")]
    answer = next((item.get("text") or "" for item in reversed(items) if item["type"] == "assistant_message"), "")
    error_items = [item for item in items if item.get("status") == "failed" and item["type"] != "tool_call"]
    steps = [entry for entry in usage["entries"] if entry["purpose"] == "step"]
    result = {
        "task": task["id"],
        "app": task["app"],
        "toolkit": app["toolkit"],
        "kind": task["kind"],
        "model": model["modelKey"],
        "coordinates": model.get("coordinateSystem"),
        "label": args.label,
        "tree": not args.no_tree,
        "sessionId": session_id,
        "status": state_after["status"],
        "timedOut": timed_out,
        "resumes": resumes,
        "success": bool(app_state is not None and task["check"](app_state)),
        "state": app_state,
        "stateObservedBeforeEnd": final_state is None and app_state is not None,
        "answer": answer[:600],
        "calls": calls,
        "screenshots": sum(call["images"] for call in calls),
        "treeCalls": sum(1 for call in calls if call["tool"] == "desktop_tree"),
        "clicks": sum(1 for call in calls if call["tool"] == "desktop_click"),
        "elementClicks": sum(1 for call in calls if call["tool"] == "desktop_click"
                             and call["arguments"].get("elementId")),
        "repeatedActions": repeated_actions(calls),
        "toolErrors": [call["error"] for call in calls if call["error"]],
        "providerError": bool(error_items) or state_after["status"] == "error",
        "requests": sum(entry["calls"] for entry in steps),
        "promptTokens": sum(entry["promptTokens"] for entry in steps),
        "cachedTokens": sum(entry.get("cachedPromptTokens") or 0 for entry in steps),
        "completionTokens": sum(entry["completionTokens"] for entry in steps),
        "costUsd": float(usage.get("estimatedCost") or 0),
        "timing": timing(calls, seconds, args.settle_ms),
        "usage": usage["entries"],
    }
    if task["kind"] == "grounding":
        result["grounding"] = grounding(task, app_state, calls)
    return result


def describe(run):
    extra = ""
    if "grounding" in run:
        extra = f" hits={run['grounding']['hits']}/{len(run['grounding']['samples'])}"
    return (f"{run['task']} {run['model']}: success={run['success']} status={run['status']} "
            f"calls={len(run['calls'])} screenshots={run['screenshots']} trees={run['treeCalls']} "
            f"elementClicks={run['elementClicks']}/{run['clicks']} prompt={run['promptTokens']} "
            f"cached={run['cachedTokens']} {run['timing']['total']}s{extra}")


def png(width, height):
    rows = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            row += bytes(((x * 7) % 256, (y * 5) % 256, ((x + y) * 3) % 256))
        rows.append(bytes(row))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"".join(rows), 6))
            + chunk(b"IEND", b""))


def upload(api, session_id, data):
    request = urllib.request.Request(f"{api.base}/sessions/{session_id}/attachments", data=data, method="POST")
    request.add_header("Content-Type", "image/png")
    request.add_header("Authorization", "Bearer " + api.token)
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read().decode())


def one_turn_prompt_tokens(api, project, model, image):
    session = api.call("POST", f"/projects/{project['id']}/sessions",
                       {"title": "Desktop image calibration", "mode": "ask", "modelId": model["id"],
                        "routing": "off", "escalation": "off"})
    body = {"text": "Reply with the single word OK.", "mode": "ask"}
    if image is not None:
        attachment = upload(api, session["id"], image)
        body["attachments"] = [{"kind": "image", "attachmentId": attachment["id"]}]
    api.call("POST", f"/sessions/{session['id']}/messages", body)
    wait_idle(api, session["id"], 300)
    steps = [entry for entry in api.call("GET", f"/sessions/{session['id']}/usage")["entries"]
             if entry["purpose"] == "step"]
    return sum(entry["promptTokens"] for entry in steps) if steps else None


def calibrate(api, project, models, out):
    rows = []
    for provider, model in models:
        baseline = one_turn_prompt_tokens(api, project, model, None)
        for width, height in CALIBRATION_SIZES:
            with_image = one_turn_prompt_tokens(api, project, model, png(width, height))
            tokens = None if baseline is None or with_image is None else with_image - baseline
            row = {"provider": provider["name"], "model": model["modelKey"], "width": width, "height": height,
                   "baseline": baseline, "withImage": with_image, "imageTokens": tokens,
                   "pixelsPerToken": round(width * height / tokens) if tokens else None}
            rows.append(row)
            print(json.dumps(row), flush=True)
    path = out / f"calibration-{stamp()}.json"
    save(path, {"rows": rows})
    print(f"Saved {path}")


def main():
    args = parse_args()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    if args.verify_fixture:
        verify_fixture(args, out)
        return
    device_id = str(uuid.uuid4())
    api = Api(args.api, args.email, args.password, device_id)
    project = bench_project(api)
    models, originals = [], []
    for spec in args.model:
        provider, model = find_model(api, spec)
        originals.append((provider, model))
        changes = {"supportsVision": True}
        if args.coordinates:
            changes["coordinateSystem"] = args.coordinates
        models.append((provider, api.call("PATCH", f"/providers/{provider['id']}/models/{model['id']}", changes)))
    try:
        if args.calibrate:
            calibrate(api, project, models, out)
            return
        if args.no_tree:
            set_tree_tool(False)
        workspace = prepare_workspace(out)
        device = Device(args, device_id, project["id"], workspace)
        path = out / f"bench-{args.label or 'run'}-{stamp()}.json"
        runs = []
        task_ids = selected_tasks(args.tasks)
        try:
            for provider, model in models:
                for task_id in task_ids:
                    task = catalog.find_task(task_id)
                    for _ in range(args.runs):
                        for attempt in range(args.retries + 1):
                            run = run_task(api, project, model, task, args, device)
                            run["provider"] = provider["name"]
                            run["attempt"] = attempt
                            print(describe(run), flush=True)
                            if not run["providerError"] or attempt == args.retries:
                                break
                            time.sleep(args.retry_wait)
                        runs.append(run)
                        save(path, {"runs": runs})
                        if args.pause:
                            time.sleep(args.pause)
        finally:
            device.stop()
            shutil.rmtree(workspace, ignore_errors=True)
        print(f"Saved {path}")
    finally:
        if args.no_tree:
            set_tree_tool(True)
        for provider, model in originals:
            api.call("PATCH", f"/providers/{provider['id']}/models/{model['id']}",
                     {"supportsVision": model["supportsVision"], "coordinateSystem": model["coordinateSystem"]})


if __name__ == "__main__":
    main()
