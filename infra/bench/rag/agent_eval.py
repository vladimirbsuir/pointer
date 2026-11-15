import argparse
import datetime
import json
import os
import re
import time
import uuid
from pathlib import Path

from run import Api, find_project

HERE = Path(__file__).resolve().parent
DEFAULT_QUESTIONS = "q01,q02,q03,q05,q08,q10,q12,q18,q21,q26"
PROMPT = (
    "Which file of this project implements the following? Answer with the file path and the main class or "
    "function, then explain in two or three sentences. Question: {query}"
)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Ask the agent the same questions with and without a semantic index and compare the turns."
    )
    parser.add_argument("--api", default=os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--email", default=os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--device-id", default=os.environ.get("POINTER_DEVICE_ID"))
    parser.add_argument("--indexed-project", required=True, help="project with a ready semantic index")
    parser.add_argument("--plain-project", required=True, help="project linked to the same folder, without index")
    parser.add_argument("--model", required=True, help="model key, for example deepseek-v4.1")
    parser.add_argument("--questions", default=str(HERE / "questions.json"))
    parser.add_argument("--ids", default=DEFAULT_QUESTIONS)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--out", default=str(HERE / "results"))
    args = parser.parse_args()
    if not (args.email and args.password and args.device_id):
        parser.error("set --email, --password and --device-id or the POINTER_* environment variables")
    return args


def find_model(api, model_key):
    for provider in api.call("GET", "/providers"):
        for model in provider["models"]:
            if model["modelKey"] == model_key and model["enabled"]:
                return model
    raise SystemExit(f"Model {model_key} not found")


def run_turn(api, project_id, model_id, text, timeout):
    session = api.call(
        "POST", f"/projects/{project_id}/sessions", {"title": "RAG agent check", "mode": "ask", "modelId": model_id}
    )
    started = time.perf_counter()
    api.call("POST", f"/sessions/{session['id']}/messages", {"text": text, "mode": "ask"})
    time.sleep(1)
    while time.perf_counter() - started < timeout:
        session = api.call("GET", f"/sessions/{session['id']}")
        if session["status"] in ("idle", "error"):
            break
        time.sleep(2)
    duration = time.perf_counter() - started
    items = api.call("GET", f"/sessions/{session['id']}/items")
    return session, items, duration


def tool_calls(items):
    return [item.get("toolName") or item.get("name") for item in items if item.get("type") == "tool_call"]


def answer(items):
    messages = [item for item in items if item.get("type") == "assistant_message"]
    return (messages[-1].get("text") or messages[-1].get("content") or "") if messages else ""


def mentions(text, expected):
    lowered = text.lower()
    for candidate in expected:
        name = Path(candidate["path"]).name.lower()
        stem = re.sub(r"\.[a-z]+$", "", name)
        if name in lowered or re.search(rf"\b{re.escape(stem)}\b", lowered):
            return True
    return False


def summarize(rows, variant):
    selected = [row for row in rows if row["variant"] == variant]
    count = len(selected)
    return {
        "questions": count,
        "correct": sum(row["correct"] for row in selected),
        "avgToolCalls": sum(len(row["toolCalls"]) for row in selected) / count,
        "avgSemanticSearches": sum(row["toolCalls"].count("semantic_search") for row in selected) / count,
        "avgInputTokens": sum(row["inputTokens"] for row in selected) / count,
        "avgOutputTokens": sum(row["outputTokens"] for row in selected) / count,
        "avgSeconds": sum(row["seconds"] for row in selected) / count,
        "failed": sum(1 for row in selected if row["status"] != "idle"),
    }


def main():
    args = parse_args()
    wanted = [identifier.strip() for identifier in args.ids.split(",")]
    questions = {
        question["id"]: question
        for question in json.loads(Path(args.questions).read_text(encoding="utf-8"))["questions"]
    }
    api = Api(args.api)
    api.sign_in(args.email, args.password, args.device_id)
    variants = {
        "with_index": find_project(api, args.indexed_project)["id"],
        "without_index": find_project(api, args.plain_project)["id"],
    }
    model = find_model(api, args.model)

    rows = []
    for identifier in wanted:
        question = questions[identifier]
        for variant, project_id in variants.items():
            session, items, duration = run_turn(
                api, project_id, model["id"], PROMPT.format(query=question["query"]), args.timeout
            )
            text = answer(items)
            row = {
                "id": identifier,
                "variant": variant,
                "status": session["status"],
                "toolCalls": tool_calls(items),
                "inputTokens": session["totalInputTokens"],
                "outputTokens": session["totalOutputTokens"],
                "seconds": round(duration, 1),
                "correct": mentions(text, question["expected"]),
                "answer": text,
            }
            rows.append(row)
            print(
                f"{identifier} {variant:13} correct={row['correct']} tools={len(row['toolCalls'])} "
                f"in={row['inputTokens']} {row['seconds']}s {row['status']}"
            )

    summary = {variant: summarize(rows, variant) for variant in variants}
    stamp = datetime.datetime.now().strftime("%Y-%m-%d-%H%M")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    result = {"createdAt": stamp, "model": args.model, "summary": summary, "rows": rows}
    (out / f"agent-{stamp}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
