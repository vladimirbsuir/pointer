import argparse
import datetime
import json
import os
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "rag"))

from run import Api, find_project  # noqa: E402

EMOJI = re.compile("[\U0001F300-\U0001FAFF\U00002600-\U000027BF\U0001F000-\U0001F2FF⭐⬆⬇✅❌]")
CYRILLIC = re.compile("[Ѐ-ӿ]")
LATIN = re.compile("[A-Za-z]")
CODE_BLOCK = re.compile(r"```.*?```", re.S)
INSTRUCTIONS_CHARS = 700


def parse_args():
    parser = argparse.ArgumentParser(description="Measure whether stated preferences are followed in new sessions.")
    parser.add_argument("--api", default=os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--email", default=os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--device-id", default=os.environ.get("POINTER_DEVICE_ID"))
    parser.add_argument("--project", default=os.environ.get("POINTER_PROJECT"))
    parser.add_argument("--model")
    parser.add_argument("--scenarios", default=str(HERE / "scenarios.json"))
    parser.add_argument("--reset", action="store_true", help="delete all active memories of the account first")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--out", default=str(HERE / "results"))
    parser.add_argument("--rescore", help="re-evaluate a saved results file with the current checks")
    args = parser.parse_args()
    if args.rescore:
        return args
    if not (args.email and args.password and args.device_id and args.project and args.model):
        parser.error("set --email, --password, --device-id, --project and --model (or the POINTER_* variables)")
    if not args.reset:
        parser.error("the baseline needs an empty memory: pass --reset to delete the account's active memories")
    return args


def find_model(api, model_key):
    for provider in api.call("GET", "/providers"):
        for model in provider["models"]:
            if model["modelKey"] == model_key and model["enabled"]:
                return model
    raise SystemExit(f"Model {model_key} not found")


def active_memories(api, project_id):
    return api.call("GET", "/memories") + api.call("GET", f"/memories?projectId={project_id}")


def reset(api, project_id):
    for memory in active_memories(api, project_id):
        api.call("DELETE", f"/memories/{memory['id']}")


def run_turn(api, project_id, model_id, text, timeout):
    session = api.call(
        "POST", f"/projects/{project_id}/sessions", {"title": "Memory benchmark", "mode": "ask", "modelId": model_id}
    )
    started = time.perf_counter()
    api.call("POST", f"/sessions/{session['id']}/messages", {"text": text, "mode": "ask"})
    time.sleep(1)
    while time.perf_counter() - started < timeout:
        session = api.call("GET", f"/sessions/{session['id']}")
        if session["status"] in ("idle", "error"):
            break
        time.sleep(2)
    items = api.call("GET", f"/sessions/{session['id']}/items")
    messages = [item for item in items if item.get("type") == "assistant_message"]
    return {
        "sessionId": session["id"],
        "status": session["status"],
        "inputTokens": session["totalInputTokens"],
        "seconds": round(time.perf_counter() - started, 1),
        "tools": [
            {"name": item.get("toolName"), "status": item.get("toolStatus"), "result": item.get("result")}
            for item in items
            if item.get("type") == "tool_call"
        ],
        "answer": (messages[-1].get("text") or "") if messages else "",
    }


def check(answer, rule):
    kind = rule["type"]
    if kind == "regex":
        return re.search(rule["value"], answer) is not None
    if kind == "not_regex":
        return re.search(rule["value"], answer) is None
    if kind == "max_lines":
        return len([line for line in answer.splitlines() if line.strip()]) <= rule["value"]
    if kind == "no_emoji":
        return EMOJI.search(answer) is None
    if kind == "min_cyrillic_share":
        prose = CODE_BLOCK.sub("", answer)
        cyrillic = len(CYRILLIC.findall(prose))
        latin = len(LATIN.findall(prose))
        return cyrillic + latin > 0 and cyrillic / (cyrillic + latin) >= rule["value"]
    raise ValueError(f"Unknown check {kind}")


def evaluate(scenario, result):
    passed = result["status"] == "idle" and all(check(result["answer"], rule) for rule in scenario["checks"])
    return {**result, "passed": passed}


def prompt_overhead_chars(memories):
    lines = [f"- [{m['kind']}, {m['scope']}] {m['name']}: {m['description']}" for m in memories]
    return INSTRUCTIONS_CHARS + sum(len(line) + 1 for line in lines)


def summarize(model, rows, memories):
    count = len(rows)
    return {
        "model": model,
        "scenarios": count,
        "baselineFollowed": sum(row["baseline"]["passed"] for row in rows),
        "captured": sum(1 for row in rows if row["teach"]["saved"]),
        "recallFollowed": sum(row["recall"]["passed"] for row in rows),
        "memoriesAfter": len(memories),
        "indexChars": prompt_overhead_chars(memories),
        "avgInputTokensBaseline": sum(row["baseline"]["inputTokens"] for row in rows) / count,
        "avgInputTokensRecall": sum(row["recall"]["inputTokens"] for row in rows) / count,
        "failedTurns": sum(
            1 for row in rows for phase in ("baseline", "teach", "recall") if row[phase]["status"] != "idle"
        ),
    }


def rescore(path, scenarios):
    saved = json.loads(Path(path).read_text(encoding="utf-8"))
    by_id = {scenario["id"]: scenario for scenario in scenarios}
    for row in saved["rows"]:
        for phase in ("baseline", "recall"):
            row[phase] = evaluate(by_id[row["id"]], row[phase])
        print(f"{row['id']:18} baseline={row['baseline']['passed']} recall={row['recall']['passed']}")
    summary = {**saved["summary"], **{
        key: value
        for key, value in summarize(saved["summary"]["model"], saved["rows"], []).items()
        if key in ("baselineFollowed", "recallFollowed", "captured")
    }}
    saved["summary"] = summary
    Path(path).write_text(json.dumps(saved, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


def main():
    args = parse_args()
    scenarios = json.loads(Path(args.scenarios).read_text(encoding="utf-8"))["scenarios"]
    if args.rescore:
        rescore(args.rescore, scenarios)
        return
    api = Api(args.api)
    api.sign_in(args.email, args.password, args.device_id)
    project_id = find_project(api, args.project)["id"]
    model_id = find_model(api, args.model)["id"]
    reset(api, project_id)

    rows = {scenario["id"]: {"id": scenario["id"]} for scenario in scenarios}
    for phase in ("baseline", "teach", "recall"):
        for scenario in scenarios:
            text = scenario["teach"] if phase == "teach" else scenario["probe"]
            result = run_turn(api, project_id, model_id, text, args.timeout)
            if phase == "teach":
                saved = [tool for tool in result["tools"] if tool["name"] == "memory_save" and tool["status"] == "succeeded"]
                result["saved"] = [tool["result"].get("name") for tool in saved]
                rows[scenario["id"]][phase] = result
                print(f"{phase:8} {scenario['id']:18} saved={result['saved']}", flush=True)
            else:
                rows[scenario["id"]][phase] = evaluate(scenario, result)
                print(f"{phase:8} {scenario['id']:18} passed={rows[scenario['id']][phase]['passed']}", flush=True)

    memories = active_memories(api, project_id)
    summary = summarize(args.model, list(rows.values()), memories)
    stamp = datetime.datetime.now().strftime("%Y-%m-%d-%H%M")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"memory-{stamp}.json").write_text(
        json.dumps({"summary": summary, "rows": list(rows.values())}, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
