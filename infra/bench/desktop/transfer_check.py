import argparse
import datetime
import importlib.util
import json
import subprocess
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


tasks = load("desktop_tasks", HERE / "tasks.py")
sandbox = load("sandbox_check", HERE / "sandbox_check.py")

TASK_IDS = ["tauri-single", "tauri-sequence", "tauri-trap"]
BINARY = "target/release/tauri-counter"
LINUX_STATE_DIR = "/tmp/state"
FIXTURE_FILES = ["Cargo.toml", "Cargo.lock", "build.rs", "tauri.conf.json", "dist/index.html", "icons/icon.png", "icons/icon.ico",
                 "src/main.rs"]
BUILD_PROMPT = (
    "This Tauri project is on my Windows computer. Send it to the Linux sandbox of this session with sandbox_sync, "
    f"then build it there with `cargo build --release` using sandbox_run with timeoutMs 600000; the first build "
    "downloads and compiles the dependencies and takes several minutes. Do not launch the application. Tell me "
    f"whether the build succeeded and whether {BINARY} exists."
)


def parse_args():
    parser = argparse.ArgumentParser(description="F7 metric 10: the Tauri tasks in the sandbox of a Windows session "
                                                 "and against the Windows build.")
    parser.add_argument("--side", choices=["linux", "windows"], required=True)
    parser.add_argument("--workspace", required=True, help="a copy of apps/tauri-counter outside this repository")
    parser.add_argument("--tasks", nargs="*", default=TASK_IDS)
    parser.add_argument("--prepare", action="store_true", help="copy the fixture into the workspace first")
    parser.add_argument("--api", default=sandbox.os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--backend", default="http://localhost:8080")
    parser.add_argument("--email", default=sandbox.os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=sandbox.os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--model", help="model key, or provider::key (linux side)")
    parser.add_argument("--build-timeout", type=int, default=1500)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--keep", action="store_true", help="keep the session and its sandbox")
    parser.add_argument("--out", default=str(HERE / "results"))
    args = parser.parse_args()
    if args.side == "linux" and not (args.email and args.password and args.model):
        parser.error("the linux side needs --model, --email and --password (or POINTER_EMAIL and POINTER_PASSWORD)")
    return args


def prepare_workspace(workspace):
    source = HERE / "apps" / "tauri-counter"
    for name in FIXTURE_FILES:
        target = workspace / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((source / name).read_bytes())
    (workspace / ".gitignore").write_text("target/\ngen/\n", encoding="utf-8", newline="\n")


def task_prompt(task, state_path):
    arguments = f" {task['arguments']}" if task.get("arguments") else ""
    return (
        f"Test the Tauri counter application of this project, already built in the sandbox as ./{BINARY}, with the "
        f"desktop tools. Start the display if it is not running, launch it with `./{BINARY} {state_path}{arguments}` "
        f"and wait for its window. Then {task['goal']} Work only through the desktop tools, not by editing files. "
        f"When you are done, tell me {task['question']}."
    )


def sandbox_exec(session_id, command):
    return subprocess.run(["docker", "exec", f"pointer-sandbox-{session_id}", "sh", "-c", command],
                          capture_output=True, text=True)


def read_linux_state(session_id, state_path):
    result = sandbox_exec(session_id, f"cat {state_path}")
    if result.returncode != 0:
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def turn(api, session_id, text, timeout):
    before = {item["id"] for item in api.call("GET", f"/sessions/{session_id}/items")}
    started = time.time()
    api.call("POST", f"/sessions/{session_id}/messages", {"text": text, "mode": "agent"})
    state = sandbox.wait_idle(api, session_id, timeout)
    items = [item for item in api.call("GET", f"/sessions/{session_id}/items") if item["id"] not in before]
    calls = [item for item in items if item["type"] == "tool_call" and not item.get("parentItemId")]
    answer = next((item.get("text") or "" for item in reversed(items) if item["type"] == "assistant_message"), "")
    return {
        "status": state["status"],
        "seconds": round(time.time() - started, 1),
        "toolCalls": [
            {"tool": call["toolName"], "status": call.get("toolStatus"),
             "seconds": sandbox.seconds_between(call.get("createdAt"), call.get("completedAt")),
             "error": (call.get("error") or {}).get("code")}
            for call in calls
        ],
        "screenshots": sum(len(call.get("images") or []) for call in calls),
        "syncs": [call.get("result") for call in calls if call["toolName"] == "sandbox_sync"],
        "answer": answer[:600],
    }


def run_linux(args, workspace):
    device_id = str(uuid.uuid4())
    api = sandbox.Api(args.api, args.email, args.password, device_id)
    project = sandbox.sandbox_project(api)
    provider, original = sandbox.find_model(api, args.model)
    model = api.call("PATCH", f"/providers/{provider['id']}/models/{original['id']}", {"supportsVision": True})
    device = sandbox.start_device(args, device_id, project["id"], workspace)
    result = {"side": "linux", "provider": provider["name"], "model": model["modelKey"], "tasks": []}
    session_id = None
    try:
        session = api.call("POST", f"/projects/{project['id']}/sessions",
                           {"title": f"Transfer check {model['modelKey']}", "mode": "agent", "modelId": model["id"],
                            "routing": "off", "escalation": "off"})
        session_id = session["id"]
        result["sessionId"] = session_id
        api.call("PATCH", f"/sessions/{session_id}", {"autoApprove": True})
        build = turn(api, session_id, BUILD_PROMPT, args.build_timeout)
        build["binary"] = sandbox_exec(session_id, f"test -x /workspace/project/{BINARY}").returncode == 0
        result["build"] = build
        print(f"build: {build['status']} binary={build['binary']} {build['seconds']}s", flush=True)
        if not build["binary"]:
            return result
        for task_id in args.tasks:
            task = tasks.find_task(task_id)
            state_path = f"{LINUX_STATE_DIR}/{task_id}.json"
            sandbox_exec(session_id, f"pkill -f {BINARY}; rm -rf {LINUX_STATE_DIR}; mkdir -p {LINUX_STATE_DIR}")
            outcome = turn(api, session_id, task_prompt(task, state_path), args.timeout)
            state = read_linux_state(session_id, state_path)
            outcome.update({"id": task_id, "state": state, "success": bool(state) and task["check"](state)})
            result["tasks"].append(outcome)
            print(f"{task_id}: {outcome['status']} success={outcome['success']} calls={len(outcome['toolCalls'])} "
                  f"screenshots={outcome['screenshots']} {outcome['seconds']}s state={state}", flush=True)
        result["usage"] = api.call("GET", f"/sessions/{session_id}/usage")["entries"]
    finally:
        device.kill()
        api.call("PATCH", f"/providers/{provider['id']}/models/{original['id']}",
                 {"supportsVision": original["supportsVision"]})
        if session_id and not args.keep:
            api.call("DELETE", f"/sessions/{session_id}")
    return result


def run_windows(args, workspace):
    executable = workspace / "target" / "release" / "tauri-counter.exe"
    if not executable.exists():
        raise SystemExit(f"Build the Windows binary first: cargo build --release in {workspace}")
    state_dir = workspace.parent / "transfer-state"
    state_dir.mkdir(exist_ok=True)
    result = {"side": "windows", "tasks": []}
    for task_id in args.tasks:
        task = tasks.find_task(task_id)
        state_path = state_dir / f"{task_id}.json"
        state_path.unlink(missing_ok=True)
        steps_path = state_dir / f"{task_id}.steps.json"
        steps_path.write_text(json.dumps(task["reference"]), encoding="utf-8")
        command = [str(executable), str(state_path)] + ([task["arguments"]] if task.get("arguments") else [])
        started = time.time()
        process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.time() + 30
            while not state_path.exists() and time.time() < deadline:
                time.sleep(0.2)
            replay = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                 str(HERE / "windows_replay.ps1"), "-ProcessId", str(process.pid), "-StepsFile", str(steps_path)],
                capture_output=True, text=True, timeout=120)
            time.sleep(0.5)
            state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else None
        finally:
            process.kill()
        outcome = {
            "id": task_id,
            "replay": (replay.stdout + replay.stderr).strip()[-400:],
            "replayExit": replay.returncode,
            "state": state,
            "success": replay.returncode == 0 and bool(state) and task["check"](state),
            "seconds": round(time.time() - started, 1),
        }
        result["tasks"].append(outcome)
        print(f"{task_id}: success={outcome['success']} exit={replay.returncode} state={state} "
              f"{outcome['seconds']}s", flush=True)
    return result


def main():
    args = parse_args()
    workspace = Path(args.workspace).resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    if args.prepare:
        prepare_workspace(workspace)
    result = run_linux(args, workspace) if args.side == "linux" else run_windows(args, workspace)
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = out / f"transfer-{args.side}-{stamp}.json"
    path.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Saved {path}")


if __name__ == "__main__":
    main()
