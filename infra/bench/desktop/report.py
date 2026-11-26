import argparse
import glob
import json
import statistics
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent


def parse_args():
    parser = argparse.ArgumentParser(description="Summarize F7 benchmark results as Markdown tables.")
    parser.add_argument("--results", default=str(HERE / "results"))
    parser.add_argument("--label", action="append", help="only runs with these labels")
    parser.add_argument("section", choices=("success", "cost", "grounding", "tree", "masking", "settle",
                                            "calibration", "failures"))
    return parser.parse_args()


def load_runs(folder, labels):
    runs = []
    for path in sorted(glob.glob(str(Path(folder) / "bench-*.json"))):
        for run in json.loads(Path(path).read_text(encoding="utf-8"))["runs"]:
            if labels and run.get("label") not in labels:
                continue
            runs.append(run)
    return runs


def median(values):
    values = [value for value in values if value is not None]
    return statistics.median(values) if values else None


def number(value, digits=0):
    if value is None:
        return ""
    if digits == 0:
        return f"{value:,.0f}"
    return f"{value:,.{digits}f}"


def table(header, rows):
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines.extend("| " + " | ".join(str(cell) for cell in row) + " |" for row in rows)
    return "\n".join(lines)


def success(runs):
    grouped = defaultdict(lambda: defaultdict(list))
    for run in runs:
        if run["kind"] == "grounding":
            continue
        grouped[(run["provider"], run["model"])][run["kind"]].append(run["success"])
    rows = []
    for (provider, model), kinds in sorted(grouped.items()):
        cells = []
        total = []
        for kind in ("single", "sequence", "trap"):
            values = kinds.get(kind, [])
            total.extend(values)
            cells.append(f"{sum(values)}/{len(values)}" if values else "")
        rate = f"{100 * sum(total) / len(total):.0f} %" if total else ""
        rows.append([provider, f"`{model}`", *cells, f"{sum(total)}/{len(total)}", rate])
    return table(["Provider", "Model", "Single", "Sequence", "Trap", "All", "Rate"], rows)


def cost(runs):
    grouped = defaultdict(list)
    for run in runs:
        if run["kind"] != "grounding":
            grouped[(run["provider"], run["model"])].append(run)
    rows = []
    for (provider, model), items in sorted(grouped.items()):
        prompt = median([run["promptTokens"] for run in items])
        cached = median([run["cachedTokens"] for run in items])
        rows.append([
            f"`{model}`",
            len(items),
            number(median([run["requests"] for run in items])),
            number(median([len(run["calls"]) for run in items])),
            number(median([run["screenshots"] for run in items])),
            number(prompt / 1000, 1) + "k" if prompt is not None else "",
            f"{100 * cached / prompt:.0f} %" if prompt else "",
            number(median([run["completionTokens"] for run in items])),
            number(median([run["timing"]["total"] for run in items])),
            number(median([run["timing"]["model"] for run in items])),
            number(median([run["timing"]["tools"] for run in items]), 1),
            number(median([run["timing"]["settle"] for run in items]), 1),
            number(median([run["timing"]["wait"] for run in items]), 1),
            number(median([run["timing"]["total"] / max(run["requests"], 1) for run in items]), 1),
        ])
    return table(["Model", "Runs", "Requests", "Tool calls", "Screenshots", "Prompt tokens", "Cached",
                  "Output tokens", "Time s", "Model s", "Tools s", "Settle s", "Wait s", "s per request"], rows)


def grounding(runs):
    grouped = defaultdict(list)
    for run in runs:
        if run["kind"] != "grounding":
            continue
        kind, size, _ = run["task"].split("-")
        layout = "dense" if kind == "griddense" else "regular"
        grouped[(run["model"], run["coordinates"], layout, size)].append(run)
    rows = []
    for (model, coordinates, layout, size), items in sorted(grouped.items()):
        samples = [sample for run in items for sample in run["grounding"]["samples"]]
        clicked = [sample for sample in samples if not sample.get("missing")]
        hits = sum(1 for sample in samples if sample["hit"])
        errors = [abs(sample["dx"]) for sample in clicked] + [abs(sample["dy"]) for sample in clicked]
        nearest = sum(1 for sample in clicked if not sample["hit"] and sample.get("clicked"))
        rows.append([f"`{model}`", coordinates, layout, size, len(items), f"{hits}/{len(samples)}",
                     f"{100 * hits / len(samples):.0f} %" if samples else "",
                     number(median([abs(sample["dx"]) for sample in clicked]), 1),
                     number(median([abs(sample["dy"]) for sample in clicked]), 1),
                     nearest, len(samples) - len(clicked), number(max(errors) if errors else None)])
    return table(["Model", "Coordinates", "Buttons", "Display", "Runs", "Hits", "Accuracy", "Median dx", "Median dy",
                  "Wrong button", "Not clicked", "Largest error px"], rows)


def tree(runs):
    grouped = defaultdict(list)
    for run in runs:
        if run["kind"] != "grounding":
            grouped[(run["model"], run["toolkit"], run["tree"])].append(run)
    rows = []
    for (model, toolkit, with_tree), items in sorted(grouped.items()):
        clicks = sum(run["clicks"] for run in items)
        rows.append([f"`{model}`", toolkit, "pixels and tree" if with_tree else "pixels only",
                     f"{sum(run['success'] for run in items)}/{len(items)}",
                     number(median([run["requests"] for run in items])),
                     number(median([run["screenshots"] for run in items])),
                     number(median([run["promptTokens"] / 1000 for run in items]), 1) + "k",
                     number(median([run["timing"]["total"] for run in items])),
                     sum(run["treeCalls"] for run in items),
                     f"{sum(run['elementClicks'] for run in items)}/{clicks}"])
    return table(["Model", "Toolkit", "Tools", "Passed", "Requests", "Screenshots", "Prompt tokens", "Time s",
                  "Tree calls", "Clicks by id"], rows)


def masking(runs):
    grouped = defaultdict(list)
    for run in runs:
        grouped[(run["label"], run["model"], run["task"])].append(run)
    rows = []
    for (label, model, task), items in sorted(grouped.items()):
        prompt = median([run["promptTokens"] for run in items])
        cached = median([run["cachedTokens"] for run in items])
        uncached = median([run["promptTokens"] - run["cachedTokens"] for run in items])
        rows.append([label, f"`{model}`", task, f"{sum(run['success'] for run in items)}/{len(items)}",
                     number(median([run["requests"] for run in items])),
                     number(median([run["screenshots"] for run in items])),
                     number(prompt / 1000, 1) + "k" if prompt else "",
                     number(cached / 1000, 1) + "k" if cached is not None else "",
                     number(uncached / 1000, 1) + "k" if uncached is not None else "",
                     sum(run["repeatedActions"] for run in items),
                     number(median([run["timing"]["total"] for run in items]))])
    return table(["Policy", "Model", "Task", "Passed", "Requests", "Screenshots", "Prompt", "Cached", "Uncached",
                  "Repeated actions", "Time s"], rows) + "\n\n" + masking_policies(runs)


def masking_policies(runs):
    grouped = defaultdict(list)
    for run in runs:
        if run["label"].startswith("keep-"):
            grouped[(run["label"], run["model"])].append(run)
    rows = []
    for (label, model), items in sorted(grouped.items()):
        requests = sum(run["requests"] for run in items)
        prompt = sum(run["promptTokens"] for run in items)
        cached = sum(run["cachedTokens"] for run in items)
        prefix = sum(entry["prefixTokens"] or 0 for run in items for entry in run["usage"])
        reuse = sum(entry["prefixReuseTokens"] or 0 for run in items for entry in run["usage"])
        rows.append([label, f"`{model}`", f"{sum(run['success'] for run in items)}/{len(items)}", requests,
                     sum(run["screenshots"] for run in items), number(prompt / requests / 1000, 2) + "k",
                     number((prompt - cached) / requests / 1000, 2) + "k",
                     number(((prompt - cached) + 0.1 * cached) / requests / 1000, 2) + "k",
                     f"{round(100 * reuse / prefix)} %" if prefix else "",
                     sum(run["repeatedActions"] for run in items)])
    return table(["Policy", "Model", "Passed", "Requests", "Screenshots", "Prompt per request",
                  "Uncached per request", "Cost-equivalent per request", "Prefix reuse", "Repeated actions"], rows)


def settle(folder):
    grouped = defaultdict(list)
    sources = []
    for path in sorted(glob.glob(str(Path(folder) / "reference-*.json"))):
        results = json.loads(Path(path).read_text(encoding="utf-8"))["results"]
        entries = [(result["toolkit"], entry) for result in results for entry in result.get("settle", [])
                   if "blinkingTiles" in entry]
        if entries:
            sources.append(Path(path).name)
        for toolkit, entry in entries:
            grouped[(toolkit, entry["action"])].append(entry)
    rows = []
    for (toolkit, action), entries in sorted(grouped.items()):
        values = sorted(entry["settledMs"] or 0 for entry in entries)
        p90 = values[min(len(values) - 1, int(round(0.9 * (len(values) - 1))))]
        rows.append([toolkit, action, len(values), number(median(values)), number(p90), number(values[-1]),
                     sum(1 for value in values if value > 300)])
    return f"From {', '.join(sources)}\n\n" + table(
        ["Toolkit", "Action", "Actions", "Median ms", "90th percentile ms", "Largest ms", "Over 300 ms"], rows)


def calibration(folder):
    rows = []
    for path in sorted(glob.glob(str(Path(folder) / "calibration-*.json"))):
        for row in json.loads(Path(path).read_text(encoding="utf-8"))["rows"]:
            rows.append([row["provider"], f"`{row['model']}`", f"{row['width']}x{row['height']}",
                         number(row["imageTokens"]), number(row["pixelsPerToken"]),
                         number(row["width"] * row["height"] / 750)])
    return table(["Provider", "Model", "Image", "Image tokens", "Pixels per token", "Estimate at 750"], rows)


def failures(runs):
    rows = []
    for run in runs:
        if run["success"] or run["kind"] == "grounding":
            continue
        rows.append([run.get("label") or "", f"`{run['model']}`", run["task"], run["status"],
                     ", ".join(sorted(set(run["toolErrors"]))), json.dumps(run["state"])[:120],
                     run["answer"][:160].replace("\n", " ").replace("|", "/")])
    return table(["Label", "Model", "Task", "Status", "Tool errors", "State", "Answer"], rows)


def main():
    args = parse_args()
    if args.section == "settle":
        print(settle(args.results))
        return
    if args.section == "calibration":
        print(calibration(args.results))
        return
    runs = load_runs(args.results, args.label)
    print({"success": success, "cost": cost, "grounding": grounding, "tree": tree, "masking": masking,
           "failures": failures}[args.section](runs))


if __name__ == "__main__":
    main()
