# idea-ui-controller

A CLI that drives JetBrains IDEs end-to-end from the command line — clicking buttons, reading the component tree, typing text, opening dialogs, and capturing screenshots — with no manual interaction required.

## Why this exists

VirtusLab builds and maintains IDE plugins across multiple JetBrains products. Every plugin ships a UI surface that has to be exercised against a real, running IDE: not unit tests, not mocked fixtures, but an actual IntelliJ window responding to real keystrokes and mouse clicks. Validating that manually before each release does not scale across teams or release cadences.

This tool wraps JetBrains' [`intellij-ui-test-robot`](https://github.com/JetBrains/intellij-ui-test-robot) in a composable CLI so that any CI pipeline, shell script, or AI agent can drive an IDE the way a human would — without a display server and without writing Java test harness code.

## VISDOM alignment

[VISDOM](https://virtuslab.com/services/visdom) is VirtusLab's framework for turning AI-driven development into a production-grade, agent-operable delivery system. It identifies three structural requirements for AI to compound productivity rather than introduce noise: **Context Fabric**, **Machine-Speed CI**, and **Automated Risk Assessment**. `idea-ui-controller` implements all three for the JetBrains IDE surface — the part of the SDLC that has historically been the hardest to reach from automation tooling.

### Context Fabric — structured, agent-consumable IDE state

AI agents cannot reason over a 10 000-line XML component tree any more than a human can scan one by eye. `getStateSummary` distils the live UI into a compact, structured list of interactive elements — buttons, fields, checkboxes, trees, dialogs — each with a ready-to-use xpath. One call gives an agent the minimum context it needs to decide the next action, at roughly 2% of the token cost of the raw `getState` output.

For elements that are invisible to the accessibility tree — canvas renders, icon-only toolbar buttons, visual layouts — `parseScreenshot` extends context into the visual domain: a YOLO model detects bounding boxes and classifies regions as `icon` or `text`; Florence-2 captions each crop; the result is the same structured JSON the agent acts on directly via `clickAt`. No element is unreachable.

`SKILL.md` is the context architecture layer for agents: a single, versioned document that describes every command, every flag, and every known footgun — updated in the same commit as every CLI change so agents always read current facts.

```
IDE component tree (raw)          → getStateSummary    → ~15 structured lines  [Context Fabric]
IDE screen pixels (visual)        → parseScreenshot    → JSON element list     [Context Fabric]
CLI API surface                   → SKILL.md           → agent instruction set [Context Fabric]
```

### Machine-Speed CI — high-frequency IDE validation loops

`runIde` spins up a fully sandboxed IDE in a couple of seconds (warm start), with no display server, no pre-existing user config, and no shared state between runs. Each sandbox is isolated: fresh temp directory, plugin injected on the spot, discarded on `stopIde`. This makes it safe to run on every pull request, not just nightly.

The CLI is synchronous and composable. Every subcommand blocks until it completes, returns a predictable exit code, and writes structured output to stdout. CI pipelines can chain commands with `&&`, branch on exit codes, and capture output with `$()` — the same primitives that compose any shell script. No polling loops, no sleep calls, no retry boilerplate.

```sh
# A CI step that validates a plugin action and fails the build if it doesn't fire
idea-ui-controller runIde "$IDE_PATH"
idea-ui-controller ideWaitSmart
idea-ui-controller sendKeyCombo --key A --modifier META --modifier SHIFT  # trigger action
result=$(idea-ui-controller editorGetText --file "Output.kt")
[[ "$result" == *"expected pattern"* ]] || exit 1
idea-ui-controller stopIde
```

### Automated Risk Assessment — scenario-based validation with accumulated learning

The `qa-ide-feature` agent orchestrator turns a natural-language feature description into 5–10 executable test scenarios, runs them sequentially against the live IDE, and produces a structured pass/fail report with root-cause notes for every failure. Between scenarios it maintains a `lessons.md` — observations about IDE behavior, discovered timing issues, and recovery strategies — that each subsequent run reads before touching the IDE. The validation loop improves with every execution.

This is not static test automation. An agent that finds a focus-loss bug on Tuesday's run embeds that knowledge as a lesson and checks for it proactively on Wednesday's. The risk coverage grows as the agent accumulates experience with the specific IDE version and plugin combination under test.

```
Feature description
       │
       ▼
qa-ide-feature (Opus)          — designs scenarios, accumulates lessons, writes report
       │  dispatches one scenario at a time
       ▼
qa-scenario-runner (Sonnet)    — drives IDE step by step, logs every command and observation
       │  returns pass / fail / blocked + log path
       ▼
report.md                      — structured evidence of feature correctness before release
```

## Powered by three AI models

`idea-ui-controller` is not a single-model tool. It assembles a purpose-built AI stack where each model does what it is best at:

| Model | Role | Why this model |
|-------|------|----------------|
| **YOLOv8** (fine-tuned, `microsoft/OmniParser-v2.0`) | Detect every clickable region in a screenshot in milliseconds | Real-time object detection trained specifically on UI screenshots — finds buttons, icons, and text regions that the accessibility tree never exposes |
| **Florence-2-base** (`microsoft/Florence-2-base`) | Caption each detected crop with a human-readable label | Microsoft's compact vision-language model runs entirely on-device (Apple Silicon MPS or CPU) — no API call, no round-trip latency, no data leaving the machine |
| **Claude Opus** (orchestration) | Design test scenarios, accumulate lessons, write reports | Maximum reasoning depth for the planning layer — where a wrong scenario design wastes the entire run |
| **Claude Sonnet** (execution) | Drive the IDE step by step, one command at a time | Deliberately a smaller, faster model: execution is a tight observe-act loop where latency compounds across dozens of steps, not a reasoning problem |

Together they form a pipeline that goes from raw pixels → structured element list → deliberate action — entirely on local hardware for the vision steps, with two tiers of Claude handling the reasoning layer: Opus where depth matters, Sonnet where speed does.

## How it works

```
┌─────────────────────────────────┐
│  idea-ui-controller (CLI)       │  ← shell, CI, or AI agent
│  Kotlin + picocli               │
└────────────┬────────────────────┘
             │  HTTP RPC
             ▼
┌─────────────────────────────────┐
│  JetBrains IDE                  │
│  IntelliJ / Rider / PyCharm …   │
│  + robot-server plugin          │  ← injected automatically by runIde
└─────────────────────────────────┘
```

`runIde` downloads and injects the `robot-server` plugin into a sandboxed IDE copy, then waits for it to become reachable. Every subsequent command communicates with that plugin over HTTP. State (PID, port, sandbox path) is kept at `~/.idea-ui-controller/state.properties`.

## Installation

```sh
./scripts/install.sh
```

Builds the self-executing fat jar, symlinks it into `~/.local/bin/idea-ui-controller`, symlinks the Claude skill into `~/.claude/skills/`, and sets up a Python venv with OmniParser dependencies for visual element detection.

**Requirements:** Java 25 (embedded in the binary — no `JAVA_HOME` setup needed after install).

## Quick start

```sh
# Launch a sandboxed IDE (~60s cold start, ~10s warm)
idea-ui-controller runIde "/Applications/IntelliJ IDEA.app"

# Structured observation (default post-action check)
idea-ui-controller getStateSummary

# Interact
idea-ui-controller clickButton --xpath "//div[@accessiblename='New Project' and @class='JButton']"
idea-ui-controller clickButton --text "Trust Project"
idea-ui-controller typeText --text "hello world\n"
idea-ui-controller sendKeyCombo --key S --modifier META          # Cmd+S

# Read the editor
idea-ui-controller editorGetText --file "Main.kt"

# Tear down
idea-ui-controller stopIde
```

## Command reference

### Daemon lifecycle
| Command | Description |
|---------|-------------|
| `runIde <path>` | Launch IDE with robot-server, register state |
| `stopIde` | Kill registered IDE and clean up sandbox |
| `probe` | Quick health check — `OK: true` means IDE is reachable |

### Observation
| Command | Description |
|---------|-------------|
| `getStateSummary` | **Default post-action check.** Focused window + compact interactive element list with ready-to-use xpaths |
| `getState [--no-tree]` | Full component tree XML; `--no-tree` for focus summary only |
| `screenshot [--output PATH]` | Capture IDE screen to PNG via robot-native method (no OS focus needed) |
| `parseScreenshot [--output PATH] [--threshold N] [--device auto\|mps\|cpu]` | Screenshot + local OmniParser v2 inference. Returns JSON `[{id, type, label, x1, y1, x2, y2, cx, cy, w, h}]` with screen-absolute pixel coordinates. YOLO for detection, Florence-2-base for captioning; runs on Apple Silicon MPS or CPU. Models (~500 MB) downloaded on first run. |

### Interaction
| Command | Description |
|---------|-------------|
| `clickButton --xpath X` / `--text T` | Find and click a component |
| `clickAt <x> <y>` | Click at absolute screen coordinates (use `cx`/`cy` from `parseScreenshot`) |
| `focusWindow [--title T]` | Bring window to front by title substring |
| `sendKey --key K [--count N]` | Dispatch a named key (`ESCAPE`, `ENTER`, `F1`, …) |
| `sendKeyCombo --key K [--modifier M]...` | Modifier+key combo (`META`, `CTRL`, `SHIFT`, `ALT`) |
| `typeText --text T` | Type a literal string one character at a time |

### Editor
| Command | Description |
|---------|-------------|
| `editorVerify` / `editorOpen --file F` | Verify editor open / click tab |
| `editorGetText` / `editorGetSelectedText` | Read document or selection |
| `editorInjectText --text T` | Replace document via write action |
| `editorMoveToLine --line N` | Move caret to 1-based line |
| `editorCaretOffset` / `editorCaretCount` | Caret position info |
| `editorCaretShape` / `editorIsBlockCursor` | Vim mode detection |

### IDE frame
| Command | Description |
|---------|-------------|
| `ideVerify` / `ideProjectName` | Verify frame / read project name |
| `ideIsSmart` / `ideWaitSmart` | Check or block until indexing completes |

### Dialogs and menus
| Command | Description |
|---------|-------------|
| `dialogVerify --title T` / `dialogTitle` | Wait for dialog / read title |
| `dialogClickButton --title T --button B` | Click button inside titled dialog |
| `actionMenuVerify` / `actionMenuItem --text T` | Top-bar menu interaction |
| `actionLinkClick --xpath X` / `actionLinkClickByText --text T` | ActionLink interaction |
| `searchEverywhereVerify` / `gutterVerify` | Verify Search Everywhere / gutter |

### Widgets
| Command | Description |
|---------|-------------|
| `listSelect --xpath X (--index N \| --text T)` | Select JList row |
| `treeSelect --xpath X (--index N \| --text T)` | Select JTree row (e.g. Project tool window) |
| `textFieldSet --label L --text T` | Set text field by adjacent JLabel |
| `checkboxSet --text T --checked true\|false` | Toggle checkbox |

### High-level flows
| Command | Description |
|---------|-------------|
| `projectCreateFromDialog --name N [--location P]` | Full New Project wizard sequence |
| `createScratchFile --name N [--language L] [--content C]` | Create and open a scratch file |

### Scripting
| Command | Description |
|---------|-------------|
| `runJs --script "..."` / `callJs --script "..."` | Arbitrary Rhino JS in the IDE process |

## AI agent integration

The repo ships two Claude subagents in `.claude/agents/`:

- **`qa-ide-feature`** (orchestrator, Opus) — given a feature description, designs 5–10 test scenarios, dispatches them to the runner, accumulates lessons between runs, and writes a `report.md`.
- **`qa-scenario-runner`** (executor, Sonnet) — drives the IDE step by step, logs every command and observation, returns a pass/fail summary.

Invoke via the `qa-ide-feature` Claude skill. `SKILL.md` at `.claude/skills/idea-ui-controller/SKILL.md` is the single source of truth agents use — updated in the same commit as every CLI change.

### Visual element detection

For elements that cannot be found through the component tree (icon-only buttons, canvas renders), `parseScreenshot` runs a full vision pipeline locally:

1. **YOLO** (`microsoft/OmniParser-v2.0/icon_detect/model.pt`) — detects bounding boxes, classifies as `icon` or `text`
2. **Florence-2-base** (`microsoft/Florence-2-base`) — captions each icon crop
3. **`clickAt <cx> <cy>`** — acts on the returned coordinates

```sh
elements=$(idea-ui-controller parseScreenshot)
# e.g. jq '.[] | select(.label | test("settings|cog"; "i")) | .cx, .cy' <<< "$elements"
idea-ui-controller clickAt 908 905
```

## Project structure

```
src/main/kotlin/com/wkeska/ideauicontroller/
  Main.kt      — daemon lifecycle, observation, screenshot, vision pipeline, keyboard primitives
  Pages.kt     — page-scoped commands: editor, dialogs, menus, widgets, flows

scripts/
  install.sh       — build + symlink binary + install Python venv
  omniparse.py     — local OmniParser inference (YOLO + Florence-2, MPS/CPU)

.claude/
  skills/idea-ui-controller/SKILL.md   — CLI reference consumed by agents (Context Fabric)
  agents/qa-ide-feature.md             — QA orchestrator (Automated Risk Assessment)
  agents/qa-scenario-runner.md         — QA executor (Machine-Speed CI driver)
```

## Building

```sh
./gradlew executable        # → build/libs/idea-ui-controller  (self-executing fat jar)
./gradlew shadowJar         # → build/libs/idea-ui-controller-fat.jar
```

Requires Java 25. The `executable` task embeds the correct `java` path from the build toolchain into the stub, so the installed binary is self-contained.
