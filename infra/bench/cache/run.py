import argparse
import datetime
import importlib.util
import json
import os
import re
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROUTING_SPEC = importlib.util.spec_from_file_location("routing_bench", HERE.parent / "routing" / "run.py")
routing = importlib.util.module_from_spec(ROUTING_SPEC)
ROUTING_SPEC.loader.exec_module(routing)
LOOKUP, TRACE, Api = routing.LOOKUP, routing.TRACE, routing.Api
find_model, find_project, mentioned = routing.find_model, routing.find_project, routing.mentioned

CODENAME = "HERON-7"
CONSTRAINT = (
    f"For this whole session: the release codename is {CODENAME}, and keep every answer under 150 words. "
)
PROBE = (
    "Without using any tools, answer from our conversation only: (1) What release codename did I give you in my "
    "first message? (2) Which file did you name as the answer to my question \"{topic}\"? Reply with two short "
    "lines."
)
DEFAULT_SCRIPT = "q01,q02,q03,q05,q08,t02,q10,q12,t03,q18,q21"
RECALLED_TURN = 5
RATE_LIMIT_WAIT = 65
MEMORY_TOPICS = [
    "gradle build", "flyway migrations", "websocket gateway", "session events", "tool permissions",
    "approval flow", "context compaction", "model routing", "usage ledger", "memory curation",
    "semantic index", "hybrid search", "worktrees", "rewind", "telegram bot", "remote runner",
    "github sign-in", "issue automation", "provider failover", "explore sub-agent",
]
MEMORY_ASPECTS = ["naming convention", "known pitfall", "test setup", "where it lives", "user preference"]


def parse_args():
    parser = argparse.ArgumentParser(description="Measure prompt caching and prefix reuse in long sessions.")
    parser.add_argument("--api", default=os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--email", default=os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--device-id", default=os.environ.get("POINTER_DEVICE_ID"))
    parser.add_argument("--project", default=os.environ.get("POINTER_PROJECT"), help="project id or name")
    parser.add_argument("--model", required=True, help="session model key")
    parser.add_argument("--provider", help="provider name, required when several providers offer the model key")
    parser.add_argument("--label", default="", help="name of the run in the results")
    parser.add_argument("--script", default=DEFAULT_SCRIPT, help="task ids of turns 1 to 11 in order")
    parser.add_argument("--turns", type=int, default=12, help="turns to run; the last full run ends with the probes")
    parser.add_argument("--memories", type=int, default=0, help="seed project memories up to this count")
    parser.add_argument("--clear-memories", action="store_true", help="delete the project's memories first")
    parser.add_argument("--embedding-model", help="model key to set as the account's embedding model first")
    parser.add_argument("--strategy", choices=["summary", "masking", "hybrid"], default="summary")
    parser.add_argument("--context-window", type=int, help="set the model's context window before the run")
    parser.add_argument("--questions", default=str(HERE.parent / "rag" / "questions.json"))
    parser.add_argument("--tasks", default=str(HERE.parent / "routing" / "tasks.json"))
    parser.add_argument("--price", default="0.5/2", help="input/output USD per million tokens")
    parser.add_argument("--ratios", default="0.1,0.25,0.5", help="cached to uncached input price ratios")
    parser.add_argument(
        "--model-prices",
        action="store_true",
        help="set --price and the first ratio as the model's prices during the run (enables the soft trigger)",
    )
    parser.add_argument("--pause", type=int, default=0, help="seconds to wait between turns")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--out", default=str(HERE / "results"))
    args = parser.parse_args()
    if not (args.email and args.password and args.device_id and args.project):
        parser.error("set --email, --password, --device-id and --project (or the POINTER_* variables)")
    return args


def load_script(args):
    questions = {
        question["id"]: {
            "prompt": LOOKUP.format(query=question["query"]),
            "topic": question["query"],
            "expected": [candidate["path"] for candidate in question["expected"]],
        }
        for question in json.loads(Path(args.questions).read_text(encoding="utf-8"))["questions"]
    }
    traces = {
        task["id"]: {
            "prompt": TRACE.format(prompt=task["prompt"]),
            "topic": task["prompt"].split(".")[0],
            "expected": task["expected"],
        }
        for task in json.loads(Path(args.tasks).read_text(encoding="utf-8"))["tasks"]
    }
    turns = []
    for identifier in (value.strip() for value in args.script.split(",")):
        task = questions.get(identifier) or traces.get(identifier)
        if task is None:
            raise SystemExit(f"Unknown task {identifier}")
        turns.append({"id": identifier, **task})
    turns[0] = {**turns[0], "prompt": CONSTRAINT + turns[0]["prompt"]}
    recalled = turns[RECALLED_TURN - 1] if len(turns) >= RECALLED_TURN else turns[-1]
    turns.append({"id": "probe", "prompt": PROBE.format(topic=recalled["topic"]), "expected": []})
    return turns[: args.turns]


def find_session_model(api, model_key, provider_name):
    matches = [
        (provider, model)
        for provider in api.call("GET", "/providers")
        for model in provider["models"]
        if model["modelKey"] == model_key and model["enabled"]
    ]
    if provider_name:
        matches = [(provider, model) for provider, model in matches if provider["name"] == provider_name]
    if not matches:
        raise SystemExit(f"Model {model_key} not found")
    if len(matches) > 1:
        names = ", ".join(provider["name"] for provider, _ in matches)
        raise SystemExit(f"Model {model_key} is offered by {names}; choose one with --provider")
    return matches[0][1]


def seed_memories(api, project_id, count):
    existing = api.call("GET", f"/memories?projectId={project_id}")
    for index in range(len(existing), count):
        topic = MEMORY_TOPICS[index % len(MEMORY_TOPICS)]
        aspect = MEMORY_ASPECTS[(index // len(MEMORY_TOPICS)) % len(MEMORY_ASPECTS)]
        name = re.sub(r"[^a-z0-9]+", "-", f"{topic} {aspect} {index}").strip("-")
        api.call(
            "POST",
            "/memories",
            {
                "projectId": project_id,
                "kind": "project",
                "name": name,
                "description": f"The {aspect} for {topic} in this repository, noted in an earlier session",
                "content": f"Benchmark note {index}: remember the {aspect} that applies to {topic}.",
            },
        )


def usage_totals(entries):
    totals = {}
    for entry in entries:
        key = (entry["modelLabel"], entry["role"], entry["purpose"])
        totals[key] = {
            "calls": entry["calls"],
            "prompt": entry["promptTokens"],
            "completion": entry["completionTokens"],
            "cached": entry.get("cachedPromptTokens") or 0,
            "cacheReported": entry.get("cacheReportedPromptTokens") or 0,
            "prefix": entry.get("prefixTokens") or 0,
            "reuse": entry.get("prefixReuseTokens") or 0,
        }
    return totals


def delta(after, before):
    result = {}
    for key, values in after.items():
        previous = before.get(key, {})
        result["/".join(key)] = {name: value - previous.get(name, 0) for name, value in values.items()}
    return {key: values for key, values in result.items() if values["calls"] > 0}


def wait_idle(api, session_id, timeout):
    started = time.perf_counter()
    time.sleep(1)
    session = api.call("GET", f"/sessions/{session_id}")
    while session["status"] not in ("idle", "error") and time.perf_counter() - started < timeout:
        time.sleep(2)
        session = api.call("GET", f"/sessions/{session_id}")
    return session, time.perf_counter() - started


def turn_answer(items, seen):
    texts = [
        item.get("text") or ""
        for item in items
        if item["id"] not in seen and item.get("type") == "assistant_message" and not item.get("parentItemId")
    ]
    return texts[-1] if texts else ""


def coverage(text, expected):
    return round(sum(mentioned(text, path) for path in expected) / len(expected), 2) if expected else None


def is_compaction(item):
    return item.get("type") == "compaction_summary" and item.get("status") == "completed"


def tool_signature(item):
    return item.get("toolName"), json.dumps(json.loads(item.get("argumentsJson") or "{}"), sort_keys=True)


def run_session(api, args, project, model, turns):
    session = api.call(
        "POST",
        f"/projects/{project['id']}/sessions",
        {
            "title": "Cache benchmark",
            "mode": "ask",
            "modelId": model["id"],
            "routing": "off",
            "contextStrategy": args.strategy,
        },
    )
    rows = []
    seen = set()
    before = {}
    peak_context = 0
    established = None
    for index, turn in enumerate(turns, start=1):
        api.call("POST", f"/sessions/{session['id']}/messages", {"text": turn["prompt"], "mode": "ask"})
        state, seconds = wait_idle(api, session["id"], args.timeout)
        if state["status"] != "idle":
            time.sleep(RATE_LIMIT_WAIT)
        items = api.call("GET", f"/sessions/{session['id']}/items")
        text = turn_answer(items, seen)
        new_items = [item for item in items if item["id"] not in seen]
        seen.update(item["id"] for item in items)
        after = usage_totals(api.call("GET", f"/sessions/{session['id']}/usage")["entries"])
        peak_context = max(peak_context, state.get("contextTokens") or 0)
        row = {
            "turn": index,
            "task": turn["id"],
            "status": state["status"],
            "seconds": round(seconds, 1),
            "contextTokens": state.get("contextTokens"),
            "coverage": coverage(text, turn["expected"]),
            "toolCalls": sum(1 for item in new_items if item.get("type") == "tool_call"),
            "compactions": sum(1 for item in new_items if is_compaction(item)),
            "softCompactions": sum(1 for item in new_items if is_compaction(item) and item.get("trigger") == "soft"),
            "usage": delta(after, before),
            "answer": text,
        }
        if index == RECALLED_TURN:
            established = turn["expected"]
        if turn["id"] == "probe":
            row["recallConstraint"] = CODENAME.lower() in text.lower()
            row["recallFile"] = bool(established) and any(mentioned(text, path) for path in established)
        rows.append(row)
        before = after
        step = row["usage"].get(f"{model['displayName']}/main/step", {})
        print(
            f"turn {index:2} {turn['id']:5} {row['seconds']:6.1f}s calls={step.get('calls', 0)} "
            f"prompt={step.get('prompt', 0)} cached={step.get('cached', 0)} reuse={step.get('reuse', 0)}/"
            f"{step.get('prefix', 0)} coverage={row['coverage']} context={row['contextTokens']}",
            flush=True,
        )
        time.sleep(args.pause)
    items = api.call("GET", f"/sessions/{session['id']}/items")
    tools = [tool_signature(item) for item in items if item.get("type") == "tool_call" and not item.get("parentItemId")]
    repeated = len(tools) - len(set(tools))
    return session["id"], rows, peak_context, repeated, len(tools)


def summarize(rows, peak_context, repeated, tool_calls, price, ratios):
    totals = {"calls": 0, "prompt": 0, "completion": 0, "cached": 0, "cacheReported": 0, "prefix": 0, "reuse": 0}
    for row in rows:
        for values in row["usage"].values():
            for name in totals:
                totals[name] += values[name]
    input_price, output_price = price
    reuse_ratio = totals["reuse"] / totals["prefix"] if totals["prefix"] else 0
    reusable = round(totals["prompt"] * reuse_ratio)
    costs = {}
    for ratio in ratios:
        cached_price = input_price * ratio
        reported = (
            (totals["prompt"] - totals["cached"]) * input_price
            + totals["cached"] * cached_price
            + totals["completion"] * output_price
        ) / 1_000_000
        ideal = (
            (totals["prompt"] - reusable) * input_price
            + reusable * cached_price
            + totals["completion"] * output_price
        ) / 1_000_000
        costs[str(ratio)] = {
            "reported": round(reported, 4),
            "perfectCache": round(ideal, 4),
            "billedPromptTokensReported": round(totals["prompt"] - totals["cached"] * (1 - ratio)),
            "billedPromptTokensPerfectCache": round(totals["prompt"] - reusable * (1 - ratio)),
        }
    uncached = (totals["prompt"] * input_price + totals["completion"] * output_price) / 1_000_000
    by_purpose = {}
    for row in rows:
        for key, values in row["usage"].items():
            purpose = by_purpose.setdefault(key.split("/")[-1], {"calls": 0, "prompt": 0, "completion": 0, "cached": 0})
            for name in purpose:
                purpose[name] += values[name]
    probe = next((row for row in rows if row["task"] == "probe"), None)
    covered = [row["coverage"] for row in rows if row["coverage"] is not None]
    return {
        "turns": len(rows),
        "failedTurns": sum(1 for row in rows if row["status"] != "idle"),
        "calls": totals["calls"],
        "promptTokens": totals["prompt"],
        "completionTokens": totals["completion"],
        "cachedPromptTokens": totals["cached"],
        "cacheReportedPromptTokens": totals["cacheReported"],
        "cacheHitRatio": round(totals["cached"] / totals["cacheReported"], 3) if totals["cacheReported"] else None,
        "prefixTokens": totals["prefix"],
        "prefixReuseTokens": totals["reuse"],
        "prefixReuseRatio": round(totals["reuse"] / totals["prefix"], 3) if totals["prefix"] else None,
        "peakContextTokens": peak_context,
        "compactions": sum(row["compactions"] for row in rows),
        "softCompactions": sum(row.get("softCompactions", 0) for row in rows),
        "toolCalls": tool_calls,
        "repeatedToolCalls": repeated,
        "avgCoverage": round(sum(covered) / len(covered), 2) if covered else None,
        "recallConstraint": probe["recallConstraint"] if probe else None,
        "recallFile": probe["recallFile"] if probe else None,
        "seconds": round(sum(row["seconds"] for row in rows), 1),
        "byPurpose": by_purpose,
        "costUncached": round(uncached, 4),
        "costByCachedPriceRatio": costs,
    }


def original_prices(model):
    names = ("inputPricePerMtok", "cachedInputPricePerMtok", "outputPricePerMtok")
    prices = {name: model.get(name) for name in names if model.get(name) is not None}
    return prices if prices else {"clearPrices": True}


def main():
    args = parse_args()
    api = Api(args.api, args.email, args.password, args.device_id)
    project = find_project(api, args.project)
    model = find_session_model(api, args.model, args.provider)
    if args.context_window:
        api.call(
            "PATCH",
            f"/providers/{model['providerId']}/models/{model['id']}",
            {"contextWindow": args.context_window},
        )
    if args.embedding_model:
        embedding = find_model(api, args.embedding_model)
        api.call("PUT", "/me/settings", {"embeddingModelId": embedding["id"]})
    if args.clear_memories:
        for memory in api.call("GET", f"/memories?projectId={project['id']}"):
            api.call("DELETE", f"/memories/{memory['id']}")
    if args.memories:
        seed_memories(api, project["id"], args.memories)
    turns = load_script(args)
    price = tuple(float(value) for value in args.price.split("/"))
    ratios = [float(value) for value in args.ratios.split(",")]
    model_path = f"/providers/{model['providerId']}/models/{model['id']}"
    if args.model_prices:
        api.call(
            "PATCH",
            model_path,
            {
                "inputPricePerMtok": price[0],
                "cachedInputPricePerMtok": round(price[0] * ratios[0], 4),
                "outputPricePerMtok": price[1],
            },
        )
    try:
        session_id, rows, peak_context, repeated, tool_calls = run_session(api, args, project, model, turns)
    finally:
        if args.model_prices:
            api.call("PATCH", model_path, original_prices(model))
    summary = summarize(rows, peak_context, repeated, tool_calls, price, ratios)
    print(json.dumps(summary, indent=2))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    label = args.label or args.model.replace(":", "-")
    result = {
        "label": label,
        "model": args.model,
        "sessionId": session_id,
        "memories": args.memories,
        "strategy": args.strategy,
        "contextWindow": args.context_window,
        "modelPrices": args.model_prices,
        "script": args.script,
        "summary": summary,
        "rows": rows,
    }
    path = out / f"cache-{label}-{stamp}.json"
    path.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Saved {path}")


if __name__ == "__main__":
    main()
