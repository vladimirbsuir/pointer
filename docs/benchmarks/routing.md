# Model routing benchmark

Measurements of smart model routing ([F3](../features/f3-model-routing.md), ROUTE-4 to ROUTE-6): what delegating exploration to the `explore` sub-agent does to the main model's context, to total tokens, cost and duration, and whether fallback lists keep turns alive during a provider outage.

## Setup

- Project: this repository at the revision that follows commit `34c65ab`, executed by the real `pointer-executor`, so tools read the actual files. No semantic index, so only file tools are used.
- Ask mode, every turn in a new session with a fixed title (no title generation), one run per task and variant, 15 November 2026.
- Main model `deepseek-v4.1-flash`: through Experiential Labs for the lookup set, through TokenHarbor (`deepseek-v4.1-flash:free`) for the trace set and for one lookup task. Sub-agent model `glm-5.3-flash` through VyceAI.
- Script: [`infra/bench/routing/run.py`](../../infra/bench/routing/run.py). Usage per model, role and purpose comes from `GET /sessions/{id}/usage`.
- Task sets:
  - **Lookup**: ten "which file implements …" questions with one expected file, the selection used in the [RAG benchmark](rag.md) (`q01, q02, q03, q05, q08, q10, q12, q18, q21, q26` of [`infra/bench/rag/questions.json`](../../infra/bench/rag/questions.json)).
  - **Trace**: six questions whose answer spans three to five files across the backend, the executor and the desktop ([`infra/bench/routing/tasks.json`](../../infra/bench/routing/tasks.json)).
- Scoring: an answer is checked for the names of the expected files; coverage is the share of expected files it names.
- Variants. The prompt fixes whether the agent delegates, so that the comparison does not depend on the model's own choice:
  - **baseline**: routing off, the prompt forbids `explore`; the main model searches and reads itself.
  - **smart**: smart routing with `glm-5.3-flash` as the fast model, the prompt asks to delegate the search to `explore`.
  - **explore**: routing off and no exploration model set, so the sub-agent runs on the session model; the prompt asks to delegate.
- Cost uses illustrative prices, because the providers' free plans have none: main model 0.50 USD per million input tokens and 2.00 per million output tokens, sub-agent model 0.10 and 0.40 (a 5 : 1 ratio).

## Delegation with a cheaper sub-agent model

Averages per turn; cost is the total of the set.

| | Lookup, baseline | Lookup, smart | Trace, baseline | Trace, smart |
|---|---|---|---|---|
| Turns completed | 10 / 10 | 10 / 10 | 6 / 6 | 6 / 6 |
| Coverage of expected files | 1.00 | 1.00 | 0.96 | 1.00 |
| Main model calls | 3.5 | 3.2 | 8.5 | 3.7 |
| Main model input tokens | 23,036 | 14,538 | 125,966 | 21,671 |
| Main model context after the turn | 9,804 | 6,292 | 20,095 | 7,196 |
| Tool calls by the main model | 4.2 | 1.9 | 14.3 | 2.7 |
| Sub-agent model calls | – | 6.9 | – | 14.0 |
| Tool calls by the sub-agent | – | 11.9 | – | 28.5 |
| Total tokens, all models | 23,640 | 57,097 | 127,835 | 305,831 |
| Share of tokens on the main model | 100 % | 26.5 % | 100 % | 7.4 % |
| Duration, s | 10.5 | 47.3 | 38.7 | 107.9 |
| Cost of the set, USD | 0.127 | 0.133 | 0.400 | 0.258 |

- **Main-model context.** Delegation cuts the main model's input tokens by 37 % on lookups and by 83 % on traces, and the context it carries into the next turn by 36 % and 64 %. The effect grows with the amount of reading a task needs: in the trace baseline the main model reads 14 results per turn and resends them on every later step.
- **Total tokens.** They rise 2.4 times in both sets. The sub-agent also resends its growing context on every call, and `glm-5.3-flash` explores far more than the task needs: on traces it uses 14 of its 15 allowed calls on average.
- **Cost.** With the 5 : 1 price ratio delegation costs 5 % more on lookups and 36 % less on traces. From the token counts, delegation breaks even when the main model is about 5.7 times more expensive than the sub-agent model on lookups and about 2.8 times on traces; below that ratio it loses money.
- **Duration.** Turns take 4.5 times longer on lookups and 2.8 times longer on traces, because the sub-agent's calls are sequential.
- **Quality.** Every turn completed and named the expected file on lookups. On traces coverage was 0.96 without and 1.00 with delegation: one baseline answer missed one of four files.

## Delegation on the same model

Trace set, `glm-5.3-flash` as both the session model and the sub-agent, routing off.

| | Baseline | Explore |
|---|---|---|
| Turns completed | 6 / 6 | 6 / 6 |
| Coverage of expected files | 0.96 | 0.96 |
| Main model calls | 7.3 | 2.3 |
| Main model input tokens | 81,957 | 9,901 |
| Main model context after the turn | 17,975 | 6,039 |
| Tool calls by the sub-agent | – | 29.0 |
| Total tokens | 83,034 | 300,876 |
| Duration, s | 52.2 | 113.5 |

Without a cheaper model delegation is a trade of total tokens for context: the main loop's input falls by 88 % and the context left for later turns by 66 %, while total tokens rise 3.6 times and the turn takes 2.2 times longer. It pays off only where the main context is the constraint, for example in long sessions close to compaction. Durations of this table are upper bounds: the run shared the executor and the provider with another run.

## Provider outage

The session model sits behind an unreachable endpoint (`http://127.0.0.1:9/v1`) and has `glm-5.3-flash` as its only fallback. Five turns per variant with the prompt "Reply with exactly: ok".

| Variant | Turns completed | Median duration, s | Model switches |
|---|---|---|---|
| Healthy: `glm-5.3-flash` directly, routing off | 5 / 5 | 3.1 | 0 |
| Outage, routing off | 0 / 5 | – | 0 |
| Outage, smart routing | 5 / 5 | 5.1 | 5 |

With routing off every turn fails within a second. With smart routing every turn completes on the fallback after one retry, about two seconds later than on a healthy provider, which is the retry delay; each turn shows one `model_switched` notice. Durations are polled every two seconds.

## Threats to validity

- One run per task and variant on sixteen tasks; model output varies between runs.
- The prompt forces or forbids delegation. Left to itself, the main model delegates only when it judges the task broad, so real sessions fall between the variants.
- Coverage checks that file names appear in the answer, not that the explanation is right.
- Prices are illustrative; the break-even ratios do not depend on them, only on the token counts and the assumption that output costs four times input for both models.
- The numbers describe `glm-5.3-flash` as the sub-agent. A model that stops earlier would shift every ratio in favour of delegation.
- Free provider plans: the lookup set ran under a limit of ten requests per minute with 30 s pauses between turns; one trace turn was repeated after a provider error; the lookup task `q26` was run through TokenHarbor after the Experiential Labs daily quota ended.

## Reproducing

Keep an executor online for the project and sign in with its device id. The account needs the main and the sub-agent model, and for the outage run a provider with an unreachable base URL and one model.

```bash
export POINTER_EMAIL=... POINTER_PASSWORD=... POINTER_DEVICE_ID=<executor device id> POINTER_PROJECT=pointer
PRICES="deepseek-v4.1-flash=0.5/2,glm-5.3-flash=0.1/0.4"
python infra/bench/routing/run.py --main deepseek-v4.1-flash --fast glm-5.3-flash --experiments tasks --set lookup --variants baseline,smart --pause 30 --prices "$PRICES"
python infra/bench/routing/run.py --main deepseek-v4.1-flash --fast glm-5.3-flash --experiments tasks --set trace --variants baseline,smart --pause 10 --prices "$PRICES"
python infra/bench/routing/run.py --main glm-5.3-flash --fast glm-5.3-flash --experiments tasks --set trace --variants baseline,explore
python infra/bench/routing/run.py --main glm-5.3-flash --fast glm-5.3-flash --dead dead-model --experiments outage
```

The script sets the account's fast model, clears its exploration and planning models, and replaces the fallback list of the `--dead` model. Results are written to `infra/bench/routing/results/`.
