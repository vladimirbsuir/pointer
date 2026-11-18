import argparse
import glob
import json
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
SIGNALS = ["repeat_call", "error_streak", "edit_thrash", "malformed_call", "denied", "read_only_streak"]
FIRING_THRESHOLDS = {
    "repeat_call": 1, "error_streak": 3, "edit_thrash": 3, "malformed_call": 1, "denied": 1, "read_only_streak": 8,
}
EVENTS = {"repeat_call", "malformed_call", "denied"}
DECAY = 0.5
GRID = [0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0]


def parse_args():
    parser = argparse.ArgumentParser(description="Summarize escalation benchmark runs and replay policies offline.")
    parser.add_argument("results", nargs="*", default=[str(HERE / "results" / "escalation-*.json")])
    parser.add_argument("--prices", default="", help="model=input/output USD per million tokens, comma-separated")
    parser.add_argument("--cached-ratio", type=float, default=1.0, help="cached to uncached input price ratio")
    parser.add_argument("--weights", default="", help="signal=weight overrides for the offline replay")
    return parser.parse_args()


def load(patterns):
    results = []
    for pattern in patterns:
        for path in sorted(glob.glob(pattern)):
            results.append(json.loads(Path(path).read_text(encoding="utf-8")))
    if not results:
        raise SystemExit("No result files found")
    return results


def parse_pairs(text, convert):
    pairs = {}
    for entry in filter(None, (part.strip() for part in text.split(","))):
        name, value = entry.rsplit("=", 1)
        pairs[name.strip()] = convert(value.strip())
    return pairs


def fires(signal, value, previous):
    reached = value >= FIRING_THRESHOLDS[signal]
    return reached and value > previous if signal in EVENTS else reached


def fired_at(history, index, signal):
    previous = history[index - 1].get(signal, 0) if index > 0 else 0
    return fires(signal, history[index].get(signal, 0), previous)


def score(history, weights):
    last = len(history) - 1
    return sum(
        weights.get(signal, 1.0) * DECAY ** (last - index)
        for signal in SIGNALS
        for index in range(last + 1)
        if fired_at(history, index, signal)
    )


def replay(run, weights, threshold):
    history = [row["signals"] for row in run["signals"]]
    for index in range(len(history) - 1):
        if score(history[: index + 1], weights) >= threshold:
            return index
    return None


def max_score(run, weights):
    history = [row["signals"] for row in run["signals"]]
    return max((score(history[: index + 1], weights) for index in range(len(history))), default=0.0)


def signal_seen(run, signal):
    history = [row["signals"] for row in run["signals"]]
    return any(fired_at(history, index, signal) for index in range(len(history)))


def auc(positives, negatives):
    if not positives or not negatives:
        return None
    wins = sum(1.0 if p > n else 0.5 if p == n else 0.0 for p in positives for n in negatives)
    return wins / (len(positives) * len(negatives))


def run_cost(run, prices, cached_ratio):
    total = 0.0
    for entry in run["usage"]:
        input_price, output_price = prices.get(entry["modelLabel"], (0.0, 0.0))
        cached = entry.get("cachedPromptTokens") or 0
        total += ((entry["promptTokens"] - cached) * input_price + cached * input_price * cached_ratio
                  + entry["completionTokens"] * output_price) / 1_000_000
    return total


def tokens(run):
    return sum(entry["promptTokens"] + entry["completionTokens"] for entry in run["usage"])


def fmt(value, digits=2):
    return "—" if value is None else f"{value:.{digits}f}"


def mean(values):
    values = list(values)
    return sum(values) / len(values) if values else None


def completed(runs):
    return [run for run in runs if run["status"] != "error"]


def policy_table(results, prices, cached_ratio):
    print("## Policies\n")
    print("| Label | Policy | Runs (errors) | Success | Cost per run, USD | Tokens per run | Seconds per run "
          "| Escalated | Escalation step |")
    print("|---|---|---|---|---|---|---|---|---|")
    for result in results:
        runs = completed(result["runs"])
        errors = len(result["runs"]) - len(runs)
        escalated = [run for run in runs if run["escalated"]]
        print(f"| {result['label']} | {result['policy']} | {len(runs)} ({errors}) "
              f"| {fmt(mean(run['success'] for run in runs))} "
              f"| {fmt(mean(run_cost(run, prices, cached_ratio) for run in runs), 4)} "
              f"| {fmt(mean(tokens(run) for run in runs), 0)} | {fmt(mean(run['seconds'] for run in runs), 1)} "
              f"| {fmt(len(escalated) / len(runs) if runs else None)} "
              f"| {fmt(mean(run['escalationStep'] for run in escalated), 1)} |")
    print()


def difficulty_table(results):
    print("## Success by difficulty\n")
    levels = ["easy", "medium", "hard"]
    print("| Label | " + " | ".join(levels) + " |")
    print("|---|" + "---|" * len(levels))
    for result in results:
        runs = completed(result["runs"])
        cells = [fmt(mean(run["success"] for run in runs if run["difficulty"] == level)) for level in levels]
        print(f"| {result['label']} | " + " | ".join(cells) + " |")
    print()


def offline(results, weights):
    fast_runs = [run for result in results if result["policy"] == "fast" for run in completed(result["runs"])]
    if not fast_runs:
        print("No fast-only runs, so no offline replay.\n")
        return {}
    failed = [run for run in fast_runs if not run["success"]]
    passed = [run for run in fast_runs if run["success"]]
    print(f"## Offline replay on {len(fast_runs)} fast-only runs ({len(failed)} failed)\n")
    print("| Predictor | AUC for a failed turn |")
    print("|---|---|")
    for signal in SIGNALS:
        value = auc([float(signal_seen(run, signal)) for run in failed],
                    [float(signal_seen(run, signal)) for run in passed])
        print(f"| {signal} | {fmt(value)} |")
    print(f"| full score (maximum over the turn) | "
          f"{fmt(auc([max_score(run, weights) for run in failed], [max_score(run, weights) for run in passed]))} |")
    print()
    print("| Threshold | Would escalate | Failures caught | Successes escalated |")
    print("|---|---|---|---|")
    for threshold in GRID:
        decisions = {id(run): replay(run, weights, threshold) is not None for run in fast_runs}
        print(f"| {threshold} | {fmt(mean(decisions.values()))} "
              f"| {fmt(mean(decisions[id(run)] for run in failed))} "
              f"| {fmt(mean(decisions[id(run)] for run in passed))} |")
    print()
    by_task = defaultdict(list)
    for run in fast_runs:
        by_task[run["task"]].append(run["success"])
    return {task: mean(values) for task, values in by_task.items()}


def timing(results, fast_success_by_task):
    escalation_runs = [run for result in results if result["policy"] == "escalation"
                       for run in completed(result["runs"])]
    if not escalation_runs:
        return
    escalated = [run for run in escalation_runs if run["escalated"]]
    stayed = [run for run in escalation_runs if not run["escalated"]]
    needless = [run for run in escalated if (fast_success_by_task.get(run["task"]) or 0) >= 0.5]
    print("## Escalation timing\n")
    print(f"- Escalated turns: {len(escalated)} of {len(escalation_runs)}; "
          f"solved after escalating: {sum(run['success'] for run in escalated)}.")
    print(f"- Escalated although the fast model alone solved the task in most fast-only runs: {len(needless)}.")
    print(f"- Missed: turns that stayed on the fast model and failed: {sum(not run['success'] for run in stayed)}.")
    fast_tokens = [
        sum(entry["promptTokens"] + entry["completionTokens"] for entry in run["usage"] if entry["role"] == "fast")
        for run in escalated
    ]
    print(f"- Mean escalation step {fmt(mean(run['escalationStep'] for run in escalated), 1)}, "
          f"mean fast-tier tokens before it {fmt(mean(fast_tokens), 0)}.")
    print()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    results = load(args.results)
    prices = {name: tuple(float(part) for part in value.split("/"))
              for name, value in parse_pairs(args.prices, str).items()}
    weights = parse_pairs(args.weights, float)
    policy_table(results, prices, args.cached_ratio)
    difficulty_table(results)
    fast_success = offline(results, weights)
    timing(results, fast_success)


if __name__ == "__main__":
    main()
