# 0030. Accessibility tree of the virtual display

Status: Accepted. Builds on [ADR 0028](0028-gui-sandbox-in-the-session-container.md).

## Context

Phase 1.5 of F7 ([GUI testing sandbox](../features/f7-gui-sandbox/README.md), GUI-8) lets the agent read the accessibility tree of the virtual display and click an element by an id instead of by coordinates read from a screenshot. On Linux the tree is AT-SPI: every toolkit with a bridge registers its applications on an accessibility bus, and a client walks their objects over D-Bus. Each object has a role, a name, a state set, screen extents and optional text, value and action interfaces.

What the prototype in the `gui` image showed before the design was fixed:

- GTK 3, Qt 6 (with `QT_LINUX_ACCESSIBILITY_ALWAYS_ON`), WebKitGTK and Chromium with `--force-renderer-accessibility` expose their controls with usable names and bounds. Tk exposes nothing.
- Electron exposes only its window frame unless it is started with `--force-renderer-accessibility`; neither `ACCESSIBILITY_ENABLED`, `org.a11y.Status.IsEnabled` nor `ScreenReaderEnabled` turns its web content on.
- Swing needs the Java ATK wrapper. Uncommenting `assistive_technologies` in the JDK's `accessibility.properties`, as the spec planned, breaks every AWT program, because the wrapper's jar is not on any class path and AWT then fails with `AWTError`. `JDK_JAVA_OPTIONS` with `-Xbootclasspath/a:` and `-Djavax.accessibility.assistive_technologies` works for any launch, at the cost of a `Picked up JDK_JAVA_OPTIONS` line on standard error.
- The accessibility bus started by D-Bus activation listens on a fixed path under `$XDG_RUNTIME_DIR` or `~/.cache`, so two displays in one container (the parallel runner tests) fight over it.
- Raw trees are noisy: Chromium reports about 200 objects for a page with a list and a field, most of them unnamed panels or hidden parts of the browser frame.

## Decision

- **A reader process, not an in-process client.** The runner embeds a Python script that uses `libatspi` through PyGObject (`python3-gi`, `gir1.2-atspi-2.0` in the `gui` image), writes it to the display's runtime directory and runs it for each `desktop_tree` and each click by element, with the display's environment and a 25-second limit. AT-SPI calls get a 1.5-second timeout so that a hung application cannot stall the walk. This keeps the runner a process manager, like the X11 tools of [ADR 0028](0028-gui-sandbox-in-the-session-container.md), avoids a D-Bus stack in the Rust crate that the Windows desktop client also builds, and costs about 0.1 second of interpreter start per call.
- **A pruned tree.** Only objects that are showing and on the screen are listed, with bounds clipped to the screen. Hidden objects are not listed but their children are still visited, because some toolkits (the GTK boxes of `tao`, under which WebKitGTK sits) mark containers as not showing while their content is. Unnamed containers (panels, fillers, sections, scroll panes) are skipped and their children move up a level; static text that repeats its parent's name or text is dropped. The walk stops at `maxNodes` (default 150), `maxDepth` (default 30), 5,000 visited objects or 12 seconds and says so.
- **Focused scope by default.** `focused` lists the window whose state is active, so a modal dialog replaces its parent in the result; `all` lists every window. When no window is active the result falls back to `all` and says so.
- **Ids by path, verified on use.** An element is keyed by its application (process id and name), its child-index path from the application, its role and its name. The runner gives each key a short id (`e12`) and keeps it for the life of the display, so the same element keeps its id across trees and the model can reuse ids from an older result. A click by id walks the path again and clicks the center of the element's current bounds only if the role and name still match and the element is showing and on the screen; otherwise it fails with `element_not_found`. A click lands with the same `xdotool` input as a coordinate click, so it shows in the screenshot and in the live view, and works where an AT-SPI action would not exist.
- **Text for the model, structure for the client.** The backend converts the bounds to the model's coordinate space and renders the tree as indented lines (`e5 button "Add note" [93, 171, 496, 34] (focused)`), which costs a fraction of the JSON; the item keeps the structured result in model space.
- **Toolkit bridges.** The session bus of a display runs with the display's runtime directory as `XDG_RUNTIME_DIR` and with `DISPLAY`, so each display gets its own accessibility bus, and `desktop_start` sets `org.a11y.Status.IsEnabled`. `desktop_launch` adds the Java ATK wrapper through `JDK_JAVA_OPTIONS`; `run_command` does not, so build output stays free of the notice. For a Chromium-based application whose tree has no document, the result says to start it with `--force-renderer-accessibility`.

## Consequences

- On the fixture applications the tree has every expected control for GTK, Qt, Chromium, Electron (with the flag) and WebKitGTK, and every control but the window frame for Swing, whose frame has no extents; Tk has none and the result says so ([desktop.md](../benchmarks/desktop.md)). Reading a tree takes 0.1–0.2 seconds.
- In the live check a model that used the tree clicked by id for most of its clicks and solved the task; another model ignored the tree and solved it by pixels. Whether the tree pays off is measured in phase 1.6.
- Ids by path can point to a different element that took the same place with the same role and name, for example a new unnamed list item inserted above an old one. The check on role and name narrows this; it is accepted because the model sees the screenshot of every click.
- Swing applications launched by `desktop_launch` print one extra line to their log, and any Java process they start inherits the variable.
- Typing became reliable as a side effect of this phase: the rebuilt image's Chromium misread `xdotool type` for characters missing from the keyboard map in five of eight runs, because `xdotool` remaps one spare key code per character. `desktop_type` now binds the missing characters of the text to free key codes in one change before typing ([03-protocol.md](../03-protocol.md) section 7.3).

## Rejected alternatives

- **The `atspi` Rust crate in the runner.** No extra process, but an async D-Bus stack in `pointer-executor`, which the Windows desktop client compiles too, for a call that is not latency-critical.
- **AT-SPI actions instead of clicks.** `doAction` works without coordinates, but action names differ by toolkit (`click`, `press`, `Press`, `doDefault`), some elements have none, and the action is invisible in the screenshot and the live view.
- **Numbering the nodes of each result afresh.** Simpler, but an id from an earlier tree would silently mean another element.
- **The full tree as JSON for the model.** Every node with its interfaces is several times the tokens of the rendered lines and mostly noise.
- **Enabling the Java wrapper in `accessibility.properties`.** Breaks every AWT program in the image, as described above.
