import argparse
import datetime
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
FIXTURE = HERE / "fixture"
REFERENCE = HERE / "reference"
ROUTING_SPEC = importlib.util.spec_from_file_location("routing_bench", HERE.parent / "routing" / "run.py")
routing = importlib.util.module_from_spec(ROUTING_SPEC)
ROUTING_SPEC.loader.exec_module(routing)
Api = routing.Api

PROJECT_NAME = "escalation-fixture"
POLICIES = ("fast", "main", "escalation")
DEVICE_BINARY = HERE.parent / "device" / "target" / "release" / (
    "pointer-bench-device.exe" if os.name == "nt" else "pointer-bench-device"
)


def parse_args():
    parser = argparse.ArgumentParser(description="Run fixture tasks under a model policy and record the outcome.")
    parser.add_argument("--api", default=os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--email", default=os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--policy", choices=POLICIES, help="fast model only, session model only, or escalation")
    parser.add_argument("--main-model", help="session model key")
    parser.add_argument("--main-provider", help="provider of the session model when the key is ambiguous")
    parser.add_argument("--fast-model", help="fast model key")
    parser.add_argument("--fast-provider", help="provider of the fast model when the key is ambiguous")
    parser.add_argument("--tasks", help="comma-separated task ids, all by default")
    parser.add_argument("--repeat", type=int, default=1, help="runs per task")
    parser.add_argument("--label", default="", help="name of the run in the results, for example the threshold")
    parser.add_argument("--workspace", default=str(HERE / "workspace"), help="folder the device works in")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--pause", type=int, default=0, help="seconds to wait between tasks")
    parser.add_argument("--retries", type=int, default=1, help="reruns of a run that ended in a provider error")
    parser.add_argument("--retry-wait", type=int, default=65, help="seconds to wait before a rerun")
    parser.add_argument("--out", default=str(HERE / "results"))
    parser.add_argument("--verify-fixture", action="store_true", help="check the fixture and reference, no models")
    args = parser.parse_args()
    if not args.verify_fixture:
        missing = [name for name in ("email", "password", "policy", "main_model", "fast_model")
                   if not getattr(args, name)]
        if missing:
            parser.error("missing " + ", ".join("--" + name.replace("_", "-") for name in missing))
    return args


def load_tasks(selected):
    tasks = json.loads((HERE / "tasks.json").read_text(encoding="utf-8"))["tasks"]
    if not selected:
        return tasks
    wanted = [value.strip() for value in selected.split(",")]
    unknown = set(wanted) - {task["id"] for task in tasks}
    if unknown:
        raise SystemExit("Unknown tasks: " + ", ".join(sorted(unknown)))
    return [task for task in tasks if task["id"] in wanted]


def reset_workspace(workspace):
    workspace.mkdir(parents=True, exist_ok=True)
    for child in workspace.iterdir():
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()
    shutil.copytree(FIXTURE, workspace, dirs_exist_ok=True)


def restore_tests(workspace):
    shutil.rmtree(workspace / "tests", ignore_errors=True)
    shutil.copytree(FIXTURE / "tests", workspace / "tests")


def run_check(workspace, modules):
    result = subprocess.run(
        [sys.executable, "-E", "-s", "-m", "unittest", "-q", "tests.test_baseline", *modules],
        cwd=workspace,
        capture_output=True,
        text=True,
        timeout=120,
    )
    return result.returncode == 0, (result.stdout + result.stderr)[-2000:]


def verify_fixture(tasks):
    failures = []
    with tempfile.TemporaryDirectory() as scratch:
        broken = Path(scratch) / "broken"
        fixed = Path(scratch) / "fixed"
        shutil.copytree(FIXTURE, broken)
        shutil.copytree(FIXTURE, fixed)
        shutil.copytree(REFERENCE, fixed, dirs_exist_ok=True)
        baseline_ok, output = run_check(broken, [])
        if not baseline_ok:
            failures.append("baseline fails on the fixture:\n" + output)
        for task in (task for task in tasks if task["kind"] == "code"):
            fails_before, _ = run_check(broken, task["check"])
            passes_after, output = run_check(fixed, task["check"])
            print(f"{task['id']:16} fixture {'fails' if not fails_before else 'PASSES'}, "
                  f"reference {'passes' if passes_after else 'FAILS'}")
            if fails_before:
                failures.append(f"{task['id']}: the check already passes on the fixture")
            if not passes_after:
                failures.append(f"{task['id']}: the reference does not pass:\n{output}")
    if failures:
        raise SystemExit("\n".join(failures))
    print("Fixture and reference are consistent.")


def find_model(api, key, provider_name):
    matches = [
        model
        for provider in api.call("GET", "/providers")
        if provider_name is None or provider["name"] == provider_name
        for model in provider["models"]
        if model["modelKey"] == key and model["enabled"]
    ]
    if len(matches) != 1:
        raise SystemExit(f"Model {key} matched {len(matches)} models; pass its provider")
    return matches[0]


def fixture_project(api):
    for project in api.call("GET", "/projects"):
        if project["name"] == PROJECT_NAME:
            return project
    return api.call("POST", "/projects", {"name": PROJECT_NAME})


def start_device(args, device_id, project_id, workspace):
    if not DEVICE_BINARY.exists():
        raise SystemExit(f"Build the bench device first: cargo build --release --manifest-path "
                         f"{HERE.parent / 'device' / 'Cargo.toml'}")
    environment = dict(os.environ)
    environment.update({
        "POINTER_API_URL": args.api.removesuffix("/api/v1"),
        "POINTER_EMAIL": args.email,
        "POINTER_PASSWORD": args.password,
        "POINTER_DEVICE_ID": device_id,
        "POINTER_PROJECT_ID": project_id,
        "POINTER_WORKSPACE": str(workspace),
    })
    device = subprocess.Popen(
        [str(DEVICE_BINARY)], env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
    )
    deadline = time.time() + 60
    while time.time() < deadline:
        line = device.stdout.readline()
        if "connected" in line:
            return device
        if device.poll() is not None:
            break
    device.kill()
    raise SystemExit("The bench device did not connect")


def session_body(args, main, fast):
    if args.policy == "fast":
        return {"modelId": fast["id"], "routing": "off", "escalation": "off"}
    if args.policy == "main":
        return {"modelId": main["id"], "routing": "off", "escalation": "off"}
    return {"modelId": main["id"], "routing": "smart", "escalation": "on"}


def wait_idle(api, session_id, timeout):
    started = time.perf_counter()
    time.sleep(2)
    session = api.call("GET", f"/sessions/{session_id}")
    while session["status"] not in ("idle", "error") and time.perf_counter() - started < timeout:
        time.sleep(2)
        session = api.call("GET", f"/sessions/{session_id}")
    return session, time.perf_counter() - started


def run_task(api, args, project, task, body, workspace):
    reset_workspace(workspace)
    session = api.call(
        "POST",
        f"/projects/{project['id']}/sessions",
        {"title": f"Escalation bench {task['id']}", "mode": "agent", **body},
    )
    api.call("PATCH", f"/sessions/{session['id']}", {"autoApprove": True})
    api.call("POST", f"/sessions/{session['id']}/messages", {"text": task["prompt"], "mode": "agent"})
    state, seconds = wait_idle(api, session["id"], args.timeout)
    items = api.call("GET", f"/sessions/{session['id']}/items")
    turn_id = next(item["turnId"] for item in items if item["type"] == "user_message")
    answer = next(
        (item.get("text") or "" for item in reversed(items)
         if item["type"] == "assistant_message" and not item.get("parentItemId")),
        "",
    )
    if task["kind"] == "code":
        restore_tests(workspace)
        success, check_output = run_check(workspace, task["check"])
    else:
        lowered = answer.lower()
        success = all(expected.lower() in lowered for expected in task["expected"])
        check_output = ""
    usage = api.call("GET", f"/sessions/{session['id']}/usage")
    signals = api.call("GET", f"/sessions/{session['id']}/turns/{turn_id}/signals")
    escalation_step = next((row["step"] for row in signals if row["escalated"]), None)
    return {
        "task": task["id"],
        "kind": task["kind"],
        "difficulty": task["difficulty"],
        "sessionId": session["id"],
        "status": state["status"],
        "success": success,
        "seconds": round(seconds, 1),
        "steps": len(signals),
        "escalated": escalation_step is not None,
        "escalationStep": escalation_step,
        "nudged": any(row.get("nudged") for row in signals),
        "usage": usage["entries"],
        "turnSteps": usage.get("turns", []),
        "signals": [
            {"step": row["step"], "model": row["modelLabel"], "signals": row["signals"], "score": row["score"]}
            for row in signals
        ],
        "toolCalls": sum(1 for item in items if item["type"] == "tool_call" and not item.get("parentItemId")),
        "answer": answer[:600],
        "checkOutput": "" if success else check_output,
    }


def main():
    args = parse_args()
    tasks = load_tasks(args.tasks)
    if args.verify_fixture:
        verify_fixture(tasks)
        return
    device_id = str(uuid.uuid4())
    api = Api(args.api, args.email, args.password, device_id)
    main_model = find_model(api, args.main_model, args.main_provider)
    fast_model = find_model(api, args.fast_model, args.fast_provider)
    project = fixture_project(api)
    workspace = Path(args.workspace).resolve()
    if (workspace / ".git").exists() or workspace == HERE.parents[2]:
        raise SystemExit("The workspace is reset before every task; point --workspace at a scratch folder")
    reset_workspace(workspace)
    settings_before = api.call("GET", "/me/settings")
    if args.policy == "escalation":
        api.call("PUT", "/me/settings", {"fastModelId": fast_model["id"]})
    device = start_device(args, device_id, project["id"], workspace)
    runs = []
    try:
        body = session_body(args, main_model, fast_model)
        for task in tasks:
            for attempt in range(args.repeat):
                run = run_task(api, args, project, task, body, workspace)
                for _ in range(args.retries):
                    if run["status"] != "error":
                        break
                    print(f"{task['id']:16} provider error, rerunning in {args.retry_wait}s", flush=True)
                    time.sleep(args.retry_wait)
                    run = run_task(api, args, project, task, body, workspace)
                run["attempt"] = attempt + 1
                runs.append(run)
                print(f"{task['id']:16} #{attempt + 1} {run['status']:6} success={run['success']!s:5} "
                      f"steps={run['steps']:2} escalated={run['escalated']!s:5} {run['seconds']:6.1f}s", flush=True)
                time.sleep(args.pause)
    finally:
        device.kill()
        if args.policy == "escalation":
            previous = settings_before.get("fastModelId")
            api.call("PUT", "/me/settings", {"fastModelId": previous} if previous else {"clearFastModel": True})
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    label = args.label or args.policy
    result = {
        "label": label,
        "policy": args.policy,
        "mainModel": args.main_model,
        "fastModel": args.fast_model,
        "runs": runs,
    }
    path = out / f"escalation-{label}-{stamp}.json"
    path.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Saved {path}")


if __name__ == "__main__":
    main()
