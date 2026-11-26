import argparse
import datetime
import importlib.util
import json
import os
import subprocess
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
BENCH = HERE.parent
ROUTING_SPEC = importlib.util.spec_from_file_location("routing_bench", BENCH / "routing" / "run.py")
routing = importlib.util.module_from_spec(ROUTING_SPEC)
ROUTING_SPEC.loader.exec_module(routing)
Api = routing.Api

PROJECT_NAME = "sandbox-check"
DEVICE = BENCH / "device" / "target" / "release" / "pointer-bench-device.exe"
EXPECTED = "391"
PROMPT = (
    "Use the Linux sandbox of this session. Install the x11-apps package there with sandbox_run (run apt-get update "
    "first), start the display, launch xcalc, compute 17 * 23 by clicking xcalc's buttons and tell me the result "
    "xcalc shows. Then run `cmd /c ver` on my computer with run_command and tell me what it prints."
)


def parse_args():
    parser = argparse.ArgumentParser(description="Live check of the F7 stage 2 sandbox of a local session.")
    parser.add_argument("--api", default=os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--backend", default="http://localhost:8080")
    parser.add_argument("--email", default=os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--model", action="append", required=True, help="model key, or provider::key")
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--workspace", default=str(HERE / "results" / "sandbox-workspace"))
    parser.add_argument("--keep", action="store_true", help="keep the sessions and their sandboxes")
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


def sandbox_project(api):
    project = next((found for found in api.call("GET", "/projects") if found["name"] == PROJECT_NAME), None)
    if project is None:
        project = api.call("POST", "/projects", {"name": PROJECT_NAME})
    return api.call("PATCH", f"/projects/{project['id']}", {"sandboxEnabled": True})


def start_device(args, device_id, project_id, workspace):
    environment = dict(os.environ)
    environment.update({
        "POINTER_API_URL": args.backend,
        "POINTER_EMAIL": args.email,
        "POINTER_PASSWORD": args.password,
        "POINTER_DEVICE_ID": device_id,
        "POINTER_PROJECT_ID": project_id,
        "POINTER_WORKSPACE": str(workspace),
    })
    log = open(workspace.parent / f"device-{device_id[:8]}.log", "w", encoding="utf-8")
    process = subprocess.Popen([str(DEVICE)], env=environment, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.time() + 60
    while time.time() < deadline:
        if "connected" in Path(log.name).read_text(encoding="utf-8", errors="replace"):
            return process
        if process.poll() is not None:
            break
        time.sleep(0.5)
    process.kill()
    raise SystemExit("The bench device did not connect; see " + log.name)


def wait_idle(api, session_id, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        session = api.call("GET", f"/sessions/{session_id}")
        if session["status"] in ("idle", "error"):
            return session
        time.sleep(2)
    api.call("POST", f"/sessions/{session_id}/cancel")
    return {"status": "timeout"}


def seconds_between(start, end):
    if not start or not end:
        return None
    parse = lambda value: datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    return round((parse(end) - parse(start)).total_seconds(), 1)


def container_state(session_id):
    name = f"pointer-sandbox-{session_id}"
    inspect = subprocess.run(
        ["docker", "inspect", "--format", "{{.Config.Image}} {{.State.Status}}", name],
        capture_output=True, text=True)
    return name, inspect.stdout.strip() if inspect.returncode == 0 else None


def run_task(api, project, provider, model, args):
    session = api.call("POST", f"/projects/{project['id']}/sessions",
                       {"title": f"Sandbox check {model['modelKey']}", "mode": "agent", "modelId": model["id"],
                        "routing": "off", "escalation": "off"})
    session_id = session["id"]
    api.call("PATCH", f"/sessions/{session_id}", {"autoApprove": True})
    started = time.time()
    api.call("POST", f"/sessions/{session_id}/messages", {"text": PROMPT, "mode": "agent"})
    state = wait_idle(api, session_id, args.timeout)
    seconds = time.time() - started
    items = api.call("GET", f"/sessions/{session_id}/items")
    calls = [item for item in items if item["type"] == "tool_call" and not item.get("parentItemId")]
    answer = next((item.get("text") or "" for item in reversed(items) if item["type"] == "assistant_message"), "")
    first_sandbox_call = next((call for call in calls
                               if call["toolName"] == "sandbox_run" or call["toolName"].startswith("desktop_")), None)
    local_commands = [call for call in calls if call["toolName"] == "run_command"]
    container, container_status = container_state(session_id)
    usage = api.call("GET", f"/sessions/{session_id}/usage")
    result = {
        "provider": provider["name"],
        "model": model["modelKey"],
        "sessionId": session_id,
        "status": state["status"],
        "seconds": round(seconds, 1),
        "container": container,
        "containerStatus": container_status,
        "firstSandboxCall": first_sandbox_call and {
            "tool": first_sandbox_call["toolName"],
            "status": first_sandbox_call.get("toolStatus"),
            "seconds": seconds_between(first_sandbox_call.get("createdAt"), first_sandbox_call.get("completedAt")),
        },
        "toolCalls": [
            {"tool": call["toolName"], "status": call.get("toolStatus"),
             "seconds": seconds_between(call.get("createdAt"), call.get("completedAt")),
             "error": (call.get("error") or {}).get("code")}
            for call in calls
        ],
        "localCommandOutput": [(call.get("result") or {}).get("output", "")[:300] for call in local_commands],
        "screenshots": sum(len(call.get("images") or []) for call in calls),
        "answer": answer[:600],
        "success": EXPECTED in answer
                   and any(call.get("toolStatus") == "succeeded" for call in calls
                           if call["toolName"] == "sandbox_run")
                   and any("Windows" in ((call.get("result") or {}).get("output") or "") for call in local_commands),
        "usage": usage["entries"],
    }
    if not args.keep:
        api.call("DELETE", f"/sessions/{session_id}")
    return result


def main():
    args = parse_args()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    workspace = Path(args.workspace).resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    device_id = str(uuid.uuid4())
    api = Api(args.api, args.email, args.password, device_id)
    project = sandbox_project(api)
    originals = [find_model(api, spec) for spec in args.model]
    models = [(provider, api.call("PATCH", f"/providers/{provider['id']}/models/{model['id']}",
                                  {"supportsVision": True}))
              for provider, model in originals]
    device = start_device(args, device_id, project["id"], workspace)
    runs = []
    try:
        for provider, model in models:
            for _ in range(args.runs):
                run = run_task(api, project, provider, model, args)
                runs.append(run)
                first = run["firstSandboxCall"] or {}
                print(f"{provider['name']}/{model['modelKey']}: {run['status']} success={run['success']} "
                      f"calls={len(run['toolCalls'])} screenshots={run['screenshots']} "
                      f"first sandbox call {first.get('tool')} {first.get('seconds')}s "
                      f"container={run['containerStatus']} {run['seconds']}s", flush=True)
    finally:
        device.kill()
        for provider, model in originals:
            api.call("PATCH", f"/providers/{provider['id']}/models/{model['id']}",
                     {"supportsVision": model["supportsVision"]})
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = out / f"sandbox-check-{stamp}.json"
    path.write_text(json.dumps({"runs": runs}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Saved {path}")


if __name__ == "__main__":
    main()
