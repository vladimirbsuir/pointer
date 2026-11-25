# 0029. Block-aligned masking of screenshots

Status: Accepted. Amends [ADR 0024](0024-append-only-context.md); builds on [ADR 0028](0028-gui-sandbox-in-the-session-container.md).

## Context

The desktop tools of F7 ([GUI testing sandbox](../features/f7-gui-sandbox/README.md)) return a screenshot after almost every action, and [ADR 0028](0028-gui-sandbox-in-the-session-container.md) puts the screenshots of a step into one user message after the step's tool results. A 1280x800 screenshot costs on the order of a thousand input tokens, far more than a typical text result, and without a limit every screenshot of a session stays in every later request. The live check of phase 1.2 sent 36–79k prompt tokens for tasks of 7–10 steps with 3–5 screenshots ([desktop.md](../benchmarks/desktop.md)).

The result masking of [F5](../features/f5-cache-aware-context.md) does not help: it replaces long text results, and a screenshot is not text. Masking each screenshot as soon as it is no longer the newest would rewrite an earlier message at every step and lose the prompt cache that [ADR 0024](0024-append-only-context.md) is built to keep.

## Decision

- **A separate counter of screenshots.** `ContextAssembler` counts the screenshots of the active context in call order and applies `MaskPolicy` to that count with its own `keep` and `block`: `pointer.desktop.keep-images` (default 3) and `pointer.desktop.image-mask-block` (default 4). The newest `keep` screenshots always stay; the boundary of masked ones moves only in whole blocks, so between two moves every request extends the previous one.
- **Under every context strategy.** The screenshot policy is one application setting and does not depend on the session's strategy; `summary` sessions mask screenshots too, while their text results stay unmasked.
- **The message keeps its shape.** A masked screenshot becomes the line `[Screenshot omitted: <toolName>(<short arguments>)]` in the same user message, under the same header, and its data URL is dropped from the message. The tool messages, the assistant's text and its tool calls are never changed, so the model keeps its own notes about what it saw. A masked screenshot is not read from the database.
- **Images are estimated from their size.** `ChatMessage` carries each image with its estimated tokens. An image whose dimensions are known (every screenshot, and every user upload since V35) counts `ceil(width × height / pointer.desktop.pixels-per-token)` tokens, default 750 pixels per token, which gives 1,366 for 1280x800; an image of unknown size keeps the old 1,000. Compaction thresholds, the soft trigger, the context meter and the prefix fingerprint all read this estimate.
- **The escalation pre-check counts visible screenshots only.** A turn starts on the step model instead of the `fast` tier when its context holds a screenshot that is not masked ([F6](../features/f6-trajectory-escalation.md), [ADR 0028](0028-gui-sandbox-in-the-session-container.md)); with `keep-images` 0 a masked history no longer forces the step model.
- **Invariant.** The invariant of [ADR 0024](0024-append-only-context.md) is extended: between compactions the request at every call extends the previous one, except at the first result or screenshot that became masked when one of the two boundaries moved. A property test checks it over generated conversations with screenshots under several pairs of result and screenshot policies, and checks that the cache-safe compaction request stays a prefix of the step request.
- **Originals stay.** Screenshots stay in the `attachments` table and in the conversation view, which looks the same with or without masking. They are removed with the session; no count or age limit is added in stage 1 ([desktop.md](../benchmarks/desktop.md) has the sizes).

## Consequences

- A long GUI task holds at most `keep + block − 1` screenshots in its requests (6 with the defaults) instead of all of them; each move of the boundary costs one uncached suffix from the first newly masked screenshot onwards, once per `block` screenshots.
- The defaults are provisional: phase 1.6 measures `keep-images` of 1, 3 and all with blocks of 1 and 4, and calibrates the pixels per token against the providers' reported usage.
- The model loses old screenshots. When it needs to see an earlier state again it has to recreate it or take a new screenshot; the ablation of phase 1.6 counts repeated actions to show what that costs.
- `ChatMessage` changed from a list of URLs to a list of images with an estimate; only the context assembler creates images, and the provider client still sends URLs only.

## Rejected alternatives

- **Masking every screenshot but the newest at every step.** The smallest context, but every step rewrites the previous screenshot message, so the whole tail of the conversation is uncached on every call. `block = 1` remains available as a setting for the ablation.
- **Reusing the result mask counter.** Screenshots and text results arrive at different rates and cost very different amounts, so one counter would either keep too many screenshots or mask text results too early.
- **Dropping the screenshot message when all of its images are masked.** It would shift every later message by one and break the prefix at the oldest step instead of at the newly masked one.
- **Downscaling old screenshots instead of omitting them.** A smaller image still costs image tokens in every request and is harder to read; a text line keeps the part the model can still use, which call produced it, at a few tokens.
