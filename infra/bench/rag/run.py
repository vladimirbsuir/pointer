import argparse
import datetime
import json
import os
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
STRATEGIES = ["vector", "lexical", "hybrid"]
CUTOFFS = [1, 5, 10]


def parse_args():
    parser = argparse.ArgumentParser(description="Compare retrieval strategies of a Pointer project index.")
    parser.add_argument("--api", default=os.environ.get("POINTER_API", "http://localhost:8080/api/v1"))
    parser.add_argument("--email", default=os.environ.get("POINTER_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("POINTER_PASSWORD"))
    parser.add_argument("--device-id", default=os.environ.get("POINTER_DEVICE_ID", str(uuid.uuid4())))
    parser.add_argument("--project", default=os.environ.get("POINTER_PROJECT"), help="project id or name")
    parser.add_argument("--questions", default=str(HERE / "questions.json"))
    parser.add_argument("--strategies", default=",".join(STRATEGIES))
    parser.add_argument("--label", default="", help="free text stored with the results")
    parser.add_argument("--out", default=str(HERE / "results"))
    args = parser.parse_args()
    if not (args.email and args.password and args.project):
        parser.error("set --email, --password and --project or POINTER_EMAIL, POINTER_PASSWORD, POINTER_PROJECT")
    return args


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method, path, body=None):
        request = urllib.request.Request(
            self.base + path,
            data=None if body is None else json.dumps(body).encode(),
            method=method,
        )
        request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(request, timeout=600) as response:
                text = response.read().decode()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as error:
            raise SystemExit(f"{method} {path} failed: {error.code} {error.read().decode()}")

    def sign_in(self, email, password, device_id):
        device = {"id": device_id, "name": "RAG benchmark", "platform": {"os": "windows"}}
        self.token = self.call(
            "POST", "/auth/login", {"email": email, "password": password, "device": device}
        )["accessToken"]


def find_project(api, reference):
    projects = api.call("GET", "/projects")
    for project in projects:
        if reference in (project["id"], project["name"]):
            return project
    raise SystemExit(f"Project {reference} not found")


def is_hit(result, expected):
    if result["path"] != expected["path"]:
        return False
    symbol = expected.get("symbol")
    return symbol is None or symbol in (result.get("symbol") or "")


def first_hit_rank(results, expected):
    for rank, result in enumerate(results, start=1):
        if any(is_hit(result, candidate) for candidate in expected):
            return rank
    return None


def percentile(values, share):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(share * (len(ordered) - 1))))]


def metrics(rows):
    ranks = [row["rank"] for row in rows]
    took = [row["tookMs"] for row in rows]
    summary = {"questions": len(rows)}
    for cutoff in CUTOFFS:
        summary[f"hit@{cutoff}"] = sum(1 for rank in ranks if rank is not None and rank <= cutoff) / len(rows)
    summary["mrr"] = sum(1 / rank for rank in ranks if rank is not None) / len(rows)
    summary["p50Ms"] = statistics.median(took)
    summary["p95Ms"] = percentile(took, 0.95)
    return summary


def commit():
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"], cwd=HERE, capture_output=True, text=True, check=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def table(title, groups):
    lines = [f"### {title}", "", "| Group | Strategy | hit@1 | hit@5 | hit@10 | MRR | p50 ms | p95 ms |",
             "|---|---|---|---|---|---|---|---|"]
    for group, by_strategy in groups.items():
        for strategy, summary in by_strategy.items():
            lines.append(
                f"| {group} ({summary['questions']}) | {strategy} | {summary['hit@1']:.2f} | {summary['hit@5']:.2f}"
                f" | {summary['hit@10']:.2f} | {summary['mrr']:.2f} | {summary['p50Ms']:.0f} | {summary['p95Ms']:.0f} |"
            )
    return "\n".join(lines)


def main():
    args = parse_args()
    questions = json.loads(Path(args.questions).read_text(encoding="utf-8"))["questions"]
    strategies = [strategy.strip() for strategy in args.strategies.split(",") if strategy.strip()]
    api = Api(args.api)
    api.sign_in(args.email, args.password, args.device_id)
    project = find_project(api, args.project)
    index = api.call("GET", f"/projects/{project['id']}/index")
    if index["status"] != "ready":
        raise SystemExit(f"The project index is {index['status']}, expected ready")

    rows = []
    for question in questions:
        for strategy in strategies:
            started = time.perf_counter()
            response = api.call(
                "POST",
                f"/projects/{project['id']}/index/query",
                {"query": question["query"], "strategy": strategy, "limit": max(CUTOFFS)},
            )
            rows.append({
                "id": question["id"],
                "lang": question["lang"],
                "kind": question["kind"],
                "strategy": strategy,
                "rank": first_hit_rank(response["results"], question["expected"]),
                "tookMs": response["tookMs"],
                "wallMs": round((time.perf_counter() - started) * 1000),
                "top": [f"{result['path']}#{result.get('symbol') or ''}" for result in response["results"][:3]],
            })
        print(question["id"], " ".join(f"{row['strategy']}={row['rank']}" for row in rows[-len(strategies):]))

    groups = {"all": {}}
    for key in ("lang", "kind"):
        for value in sorted({row[key] for row in rows}):
            groups[f"{key}={value}"] = {}
    for strategy in strategies:
        selected = [row for row in rows if row["strategy"] == strategy]
        groups["all"][strategy] = metrics(selected)
        for key in ("lang", "kind"):
            for value in sorted({row[key] for row in selected}):
                groups[f"{key}={value}"][strategy] = metrics([row for row in selected if row[key] == value])

    stamp = datetime.datetime.now().strftime("%Y-%m-%d-%H%M")
    result = {
        "createdAt": datetime.datetime.now().isoformat(timespec="seconds"),
        "label": args.label,
        "commit": commit(),
        "index": {key: index[key] for key in ("embeddingModelKey", "fileCount", "chunkCount", "indexedAt")},
        "summary": groups,
        "rows": rows,
    }
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"results-{stamp}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    title = f"{index['embeddingModelKey']}, {index['chunkCount']} chunks, commit {result['commit']} {args.label}".strip()
    markdown = table(title, groups)
    (out / f"results-{stamp}.md").write_text(markdown + "\n", encoding="utf-8")
    print()
    print(markdown)


if __name__ == "__main__":
    main()
