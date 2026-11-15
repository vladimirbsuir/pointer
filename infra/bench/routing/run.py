import argparse
import datetime
import json
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_QUESTIONS = "q01,q02,q03,q05,q08,q10,q12,q18,q21,q26"
LOOKUP = (
    "Which file of this project implements the following? Answer with the file path and the main class or "
    "function, then explain in two or three sentences. Question: {query}"
)
TRACE = "{prompt} Name every file involved with its path and keep the answer under 200 words."
RATE_LIMIT_WAIT = 65
DELEGATE = " Delegate the search to the explore tool and answer from its report."
NO_DELEGATE = " Do not use the explore tool; search with the other tools yourself."
VARIANTS = {
    "baseline": {"routing": "off", "suffix": NO_DELEGATE},
    "explore": {"routing": "off", "suffix": DELEGATE},
    "smart": {"routing": "smart", "suffix": DELEGATE},
}
OUTAGE_PROMPT = "Reply with exactly: ok"


def parse_args():
    parser = argparse.ArgumentParser(description="Measure model routing: delegation, cost split and failover.")
    parser.add_argument("--api", default=os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--email", default=os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--device-id", default=os.environ.get("POINTER_DEVICE_ID"))
    parser.add_argument("--project", default=os.environ.get("POINTER_PROJECT"), help="project id or name")
    parser.add_argument("--main", required=True, help="session model key")
    parser.add_argument("--fast", required=True, help="fast model key, also the fallback in the outage run")
    parser.add_argument("--dead", help="model key behind an unreachable endpoint for the outage run")
    parser.add_argument("--experiments", default="tasks,outage")
    parser.add_argument("--variants", default=",".join(VARIANTS))
    parser.add_argument("--set", choices=["lookup", "trace"], default="lookup", help="task set")
    parser.add_argument("--questions", default=str(HERE.parent / "rag" / "questions.json"))
    parser.add_argument("--tasks", default=str(HERE / "tasks.json"))
    parser.add_argument("--ids", default="", help="task ids; default is the built-in selection of the set")
    parser.add_argument("--pause", type=int, default=0, help="seconds to wait between turns")
    parser.add_argument("--outage-turns", type=int, default=5)
    parser.add_argument(
        "--prices",
        default="",
        help="USD per million tokens as model=input/output, comma separated, for example main-model=0.5/2",
    )
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--out", default=str(HERE / "results"))
    args = parser.parse_args()
    if not (args.email and args.password and args.device_id and args.project):
        parser.error("set --email, --password, --device-id and --project (or the POINTER_* variables)")
    return args


class Api:
    def __init__(self, base, email, password, device_id):
        self.base = base.rstrip("/")
        self.credentials = {
            "email": email,
            "password": password,
            "device": {"id": device_id, "name": "Routing benchmark", "platform": {"os": "windows"}},
        }
        self.token = None
        self.sign_in()

    def sign_in(self):
        self.token = None
        self.token = self.call("POST", "/auth/login", self.credentials)["accessToken"]

    def call(self, method, path, body=None, retry=True):
        request = urllib.request.Request(
            self.base + path, data=None if body is None else json.dumps(body).encode(), method=method
        )
        request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(request, timeout=600) as response:
                text = response.read().decode()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as error:
            if error.code == 401 and retry and self.token:
                self.sign_in()
                return self.call(method, path, body, retry=False)
            raise SystemExit(f"{method} {path} failed: {error.code} {error.read().decode()}")


def find_project(api, wanted):
    for project in api.call("GET", "/projects"):
        if wanted in (project["id"], project["name"]):
            return project
    raise SystemExit(f"Project {wanted} not found")


def find_model(api, model_key):
    for provider in api.call("GET", "/providers"):
        for model in provider["models"]:
            if model["modelKey"] == model_key and model["enabled"]:
                return model
    raise SystemExit(f"Model {model_key} not found")


def parse_prices(text):
    prices = {}
    for entry in filter(None, (part.strip() for part in text.split(","))):
        model, pair = entry.split("=")
        prompt, completion = pair.split("/")
        prices[model] = (float(prompt), float(completion))
    return prices


def run_turn(api, project_id, model_id, routing, text, timeout):
    session = api.call(
        "POST",
        f"/projects/{project_id}/sessions",
        {"title": "Routing benchmark", "mode": "ask", "modelId": model_id, "routing": routing},
    )
    started = time.perf_counter()
    api.call("POST", f"/sessions/{session['id']}/messages", {"text": text, "mode": "ask"})
    time.sleep(1)
    while time.perf_counter() - started < timeout:
        session = api.call("GET", f"/sessions/{session['id']}")
        if session["status"] in ("idle", "error"):
            break
        time.sleep(2)
    seconds = time.perf_counter() - started
    usage = api.call("GET", f"/sessions/{session['id']}/usage")["entries"]
    waited = 0
    while session["status"] == "idle" and waited < 15 and not any(entry["purpose"] == "title" for entry in usage):
        time.sleep(3)
        waited += 3
        usage = api.call("GET", f"/sessions/{session['id']}/usage")["entries"]
    items = api.call("GET", f"/sessions/{session['id']}/items")
    return session, items, usage, seconds


def answer(items):
    messages = [
        item for item in items if item.get("type") == "assistant_message" and not item.get("parentItemId")
    ]
    return (messages[-1].get("text") or "") if messages else ""


def mentioned(text, path):
    lowered = text.lower()
    name = Path(path).name.lower()
    stem = re.sub(r"\.[a-z]+$", "", name)
    if stem in ("lib", "mod"):
        return "/".join(Path(path).parts[-3:]).lower() in lowered.replace("\\", "/")
    return name in lowered or re.search(rf"\b{re.escape(stem)}\b", lowered) is not None


def coverage(text, expected):
    return sum(mentioned(text, path) for path in expected) / len(expected)


def load_tasks(args):
    if args.set == "trace":
        tasks = [
            {"id": task["id"], "prompt": TRACE.format(prompt=task["prompt"]), "expected": task["expected"]}
            for task in json.loads(Path(args.tasks).read_text(encoding="utf-8"))["tasks"]
        ]
        wanted = args.ids
    else:
        tasks = [
            {
                "id": question["id"],
                "prompt": LOOKUP.format(query=question["query"]),
                "expected": [candidate["path"] for candidate in question["expected"]],
            }
            for question in json.loads(Path(args.questions).read_text(encoding="utf-8"))["questions"]
        ]
        wanted = args.ids or DEFAULT_QUESTIONS
    if not wanted:
        return tasks
    ids = [value.strip() for value in wanted.split(",")]
    return [task for task in tasks if task["id"] in ids]


def cost(entries, prices):
    total = 0.0
    for entry in entries:
        prompt, completion = prices.get(entry["modelLabel"], (0.0, 0.0))
        total += (entry["promptTokens"] * prompt + entry["completionTokens"] * completion) / 1_000_000
    return total


def task_row(identifier, variant, session, items, usage, seconds, expected, main_label, prices):
    tools = [item for item in items if item.get("type") == "tool_call"]
    main_steps = [entry for entry in usage if entry["purpose"] == "step" and entry["modelLabel"] == main_label]
    main_prompt = sum(entry["promptTokens"] for entry in main_steps)
    main_calls = sum(entry["calls"] for entry in main_steps)
    main_tokens = sum(
        entry["promptTokens"] + entry["completionTokens"] for entry in usage if entry["modelLabel"] == main_label
    )
    total_tokens = sum(entry["promptTokens"] + entry["completionTokens"] for entry in usage)
    text = answer(items)
    return {
        "id": identifier,
        "variant": variant,
        "status": session["status"],
        "correct": coverage(text, expected) > 0,
        "coverage": round(coverage(text, expected), 2),
        "seconds": round(seconds, 1),
        "exploreCalls": sum(1 for tool in tools if tool.get("toolName") == "explore"),
        "mainToolCalls": sum(
            1 for tool in tools if not tool.get("parentItemId") and tool.get("toolName") != "explore"
        ),
        "nestedToolCalls": sum(1 for tool in tools if tool.get("parentItemId")),
        "mainSteps": main_calls,
        "mainPromptTokens": main_prompt,
        "mainPromptPerStep": round(main_prompt / main_calls) if main_calls else 0,
        "finalContextTokens": session["contextTokens"],
        "mainTokens": main_tokens,
        "totalTokens": total_tokens,
        "costUsd": round(cost(usage, prices), 6),
        "usage": usage,
        "answer": text,
    }


def average(rows, key):
    return round(sum(row[key] for row in rows) / len(rows), 1) if rows else 0


def summarize_tasks(rows, variant):
    selected = [row for row in rows if row["variant"] == variant]
    finished = [row for row in selected if row["status"] == "idle"]
    total_tokens = sum(row["totalTokens"] for row in finished)
    return {
        "turns": len(selected),
        "failed": len(selected) - len(finished),
        "retried": sum(1 for row in selected if row["retried"]),
        "correct": sum(row["correct"] for row in finished),
        "avgCoverage": round(sum(row["coverage"] for row in finished) / len(finished), 2) if finished else 0,
        "delegated": sum(1 for row in finished if row["exploreCalls"] > 0),
        "avgSeconds": average(finished, "seconds"),
        "avgMainSteps": average(finished, "mainSteps"),
        "avgMainToolCalls": average(finished, "mainToolCalls"),
        "avgNestedToolCalls": average(finished, "nestedToolCalls"),
        "avgMainPromptTokens": average(finished, "mainPromptTokens"),
        "avgMainPromptPerStep": average(finished, "mainPromptPerStep"),
        "avgFinalContextTokens": average(finished, "finalContextTokens"),
        "avgTotalTokens": average(finished, "totalTokens"),
        "mainTokenShare": round(sum(row["mainTokens"] for row in finished) / total_tokens, 3) if total_tokens else 0,
        "totalCostUsd": round(sum(row["costUsd"] for row in finished), 4),
    }


def run_tasks(api, args, project, main, fast, prices):
    api.call("PUT", "/me/settings", {"fastModelId": fast["id"], "clearExploreModel": True, "clearPlanModel": True})
    variants = [name.strip() for name in args.variants.split(",")]
    rows = []
    for task in load_tasks(args):
        for variant in variants:
            setup = VARIANTS[variant]
            retried = False
            while True:
                session, items, usage, seconds = run_turn(
                    api, project["id"], main["id"], setup["routing"], task["prompt"] + setup["suffix"], args.timeout
                )
                if session["status"] == "idle" or retried:
                    break
                retried = True
                time.sleep(RATE_LIMIT_WAIT)
            row = task_row(
                task["id"], variant, session, items, usage, seconds, task["expected"], main["displayName"], prices
            )
            row["retried"] = retried
            rows.append(row)
            time.sleep(args.pause)
            print(
                f"{task['id']} {variant:9} coverage={row['coverage']} explore={row['exploreCalls']} "
                f"mainSteps={row['mainSteps']} mainPrompt={row['mainPromptTokens']} total={row['totalTokens']} "
                f"{row['seconds']}s {row['status']}",
                flush=True,
            )
    return {"summary": {variant: summarize_tasks(rows, variant) for variant in variants}, "rows": rows}


def outage_turns(api, project, model, routing, count, timeout, label):
    rows = []
    for index in range(count):
        session, items, usage, seconds = run_turn(api, project["id"], model["id"], routing, OUTAGE_PROMPT, timeout)
        rows.append({
            "variant": label,
            "status": session["status"],
            "seconds": round(seconds, 1),
            "switched": sum(1 for item in items if item.get("type") == "model_switched"),
            "answeredBy": sorted({entry["modelLabel"] for entry in usage if entry["purpose"] == "step"}),
        })
        print(f"outage {label:12} {index + 1}/{count} {rows[-1]['status']} {rows[-1]['seconds']}s", flush=True)
    return rows


def summarize_outage(rows):
    finished = [row for row in rows if row["status"] == "idle"]
    return {
        "turns": len(rows),
        "completed": len(finished),
        "avgSeconds": average(finished, "seconds"),
        "avgSecondsAll": average(rows, "seconds"),
        "switches": sum(row["switched"] for row in rows),
    }


def run_outage(api, args, project, fast):
    dead = find_model(api, args.dead)
    api.call("PUT", f"/models/{dead['id']}/fallbacks", {"modelIds": [fast["id"]]})
    runs = {
        "healthy": outage_turns(api, project, fast, "off", args.outage_turns, args.timeout, "healthy"),
        "outage_off": outage_turns(api, project, dead, "off", args.outage_turns, args.timeout, "outage_off"),
        "outage_smart": outage_turns(api, project, dead, "smart", args.outage_turns, args.timeout, "outage_smart"),
    }
    return {
        "summary": {label: summarize_outage(rows) for label, rows in runs.items()},
        "rows": [row for rows in runs.values() for row in rows],
    }


def main():
    args = parse_args()
    api = Api(args.api, args.email, args.password, args.device_id)
    project = find_project(api, args.project)
    main_model = find_model(api, args.main)
    fast = find_model(api, args.fast)
    prices = parse_prices(args.prices)
    experiments = [name.strip() for name in args.experiments.split(",")]
    result = {
        "main": args.main,
        "fast": args.fast,
        "set": args.set,
        "prices": {model: {"input": pair[0], "output": pair[1]} for model, pair in prices.items()},
    }
    if "tasks" in experiments:
        result["tasks"] = run_tasks(api, args, project, main_model, fast, prices)
        print(json.dumps(result["tasks"]["summary"], indent=2))
    if "outage" in experiments:
        if not args.dead:
            raise SystemExit("--dead is required for the outage run")
        result["outage"] = run_outage(api, args, project, fast)
        print(json.dumps(result["outage"]["summary"], indent=2))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"routing-{datetime.datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved {path}")


if __name__ == "__main__":
    main()
