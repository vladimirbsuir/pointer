# Memory benchmark

Preference retention of the agent memory ([F4](../features/f4-agent-memory.md), MEM-1 to MEM-3): does a preference stated in one session apply in a new one?

## Setup

- Model `glm-5.3-flash` through VyceAI, Ask mode, embeddings `nomic-embed-text` through local Ollama, one run per session.
- Project: this repository at commit `0e27d16`, executed by the real `pointer-executor`, so the agent can read files.
- Scenarios: [`infra/bench/memory/scenarios.json`](../../infra/bench/memory/scenarios.json), ten preferences, each with a teaching message, a probe task and automatic checks of the answer (regular expressions, line count, emoji, share of Cyrillic letters).
- Procedure of [`infra/bench/memory/run.py`](../../infra/bench/memory/run.py), each turn in a new session:
  1. **Baseline**: all memories of the account are deleted, every probe is asked.
  2. **Teach**: every preference is stated once, in Russian, without asking to remember it; the run records whether the agent called `memory_save`.
  3. **Recall**: every probe is asked again, with the memories saved in step 2.

## Results

| Scenario | Preference | Baseline | Saved in teach | Recall |
|---|---|---|---|---|
| p01 | Answer in Russian even to English questions | no | yes | yes |
| p02 | pnpm, never npm or yarn | yes¹ | yes | yes |
| p03 | At most five lines, no introductions | no | yes | yes |
| p04 | No emoji | yes | yes | yes |
| p05 | Dates as DD.MM.YYYY | no | yes | yes |
| p06 | Branches as `feature/<task>-<slug>` | no | yes | yes |
| p07 | Task number at the end of commit messages, `(#123)` | yes² | yes² | yes |
| p08 | Vitest for frontend tests | yes¹ | yes | yes |
| p09 | Estimates in hours, not days | no | yes | yes³ |
| p10 | Python examples with pathlib and type hints, no `os.path` | no | yes | yes |
| | **Total** | **4 / 10** | **10 / 10** | **10 / 10** |

1. Derivable from the repository: `desktop/package.json` declares pnpm, and Vitest follows from the Vite setup. These two scenarios do not isolate memory.
2. The probe "fixing the login timeout, task 77" was enough: in the baseline the agent wrote `(#77)` and saved the convention itself; in the teach step it found the memory and called `memory_read` instead of `memory_save`.
3. The first scoring failed this answer because the check looked for "час" and the answer wrote "2–3 ч"; the check was fixed and the saved results re-scored with `run.py --rescore`. All answers were also read by hand.

Excluding p02 and p08, memory turns 2 of 8 followed preferences into 8 of 8.

| Measure | Baseline | Recall |
|---|---|---|
| Median input tokens per turn | 9,967 | 6,294 |
| Tool calls, all ten probes | 53 | 20 |
| Median turn time | 9.2 s | 9.1 s |

- The memory index costs about 270 input tokens with ten memories: on the probes that used no tools (p01, p05, p10) the recall turns took 256 to 279 tokens more than the baseline turns. The index of ten memories is 1,681 characters.
- With the preferences known the agent explored the repository less: 20 tool calls instead of 53, mostly because it no longer searched for conventions (p07, p09) before answering.
- The agent saved every preference in a well-formed memory without being asked to remember it: kind `feedback` with a kebab-case name and a one-line description, for example `time-estimates-in-hours` and `branch-naming-convention`.
- One run, one model and ten scenarios are a small sample; the numbers show the effect, not its size for other models.

## Reproducing

Keep an executor (the desktop app) online for the project, then:

```bash
POINTER_EMAIL=... POINTER_PASSWORD=... POINTER_DEVICE_ID=<executor device id> POINTER_PROJECT=pointer python infra/bench/memory/run.py --model glm-5.3-flash --reset
```

`--reset` deletes all active memories of the account (they can be restored with undo); use a test account. Results go to `infra/bench/memory/results/` (ignored by git); `--rescore <file>` re-evaluates a results file with the current checks.
