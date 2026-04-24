# idea-ui-controller — Specification

A command-line tool that launches a JetBrains IDE with a UI-automation plugin
bolted on and drives it over RPC. Intended for automated verification of IDE
plugins, scripting reproducers, and letting agents interact with a real IDE
window the same way a human would.

Built on JetBrains'
[`intellij-ui-test-robot`](https://github.com/JetBrains/intellij-ui-test-robot)
(not the Python Robot Framework — unrelated). All locator syntax (`byXpath`)
and RPC primitives (`callJs`, `runJs`) come from there.

## Runtime model

- **Daemon.** The IDE process is long-lived; each CLI invocation is a single
  synchronous action against it. One IDE at a time.
- **State file.** `~/.idea-ui-controller/state.properties` holds the active
  daemon's `pid`, `port`, and sandbox directory.
- **Sandbox.** Every `runIde` creates a fresh temp sandbox directory,
  installs the robot-server plugin (plus any `--plugin` archives) into it,
  and tears it down on `stopIde`. Sandboxes are **not** carried forward
  between daemons — callers must pass `--plugin` on every relaunch.
- **Exit codes.** `0` = success, non-zero = failure.
- **Synchronous.** Every subcommand blocks until its action has completed on
  the IDE side. Callers must not insert their own `sleep`s.

## Consumers

### Skill
`.claude/skills/idea-ui-controller/SKILL.md` teaches a Claude Code agent how
to use every CLI command. When the CLI changes, the skill changes in the
same commit — the agent has no other source of truth.

### QA agents
Two Claude subagents that automate end-to-end IDE QA via the CLI:

- **`qa-ide-feature`** (orchestrator) — takes a feature description, gathers
  context from source + a live IDE, designs several test scenarios,
  dispatches them one at a time to the runner, accumulates lessons between
  runs, and writes a final report. Never drives the IDE itself.
- **`qa-scenario-runner`** (executor) — receives one scenario at a time,
  drives the IDE step by step via the CLI, writes a detailed log to a path
  the orchestrator specifies, and returns a short status summary.

`qa-ide-feature` is the user-facing entry point; `qa-scenario-runner` is
internal to it.

## Runtime requirements

- **Java 25.** The fat-jar stub passes the required `--add-opens` flags.
- **IDE path.** On macOS, the `.app` bundle directory (e.g.
  `IntelliJ IDEA.app`). On Linux/Windows, the unpacked distribution dir.

## Command surface

### Daemon lifecycle

- **`runIde <idePath> [--port N] [--timeout-seconds N] [--plugin PATH]...`**
  Launch the IDE with the robot-server plugin (and any extra plugins passed
  via `--plugin`), wait until the robot-server responds, and register the
  daemon. `--plugin` accepts either a `.zip` archive or an unpacked plugin
  directory — directory form is auto-zipped into the sandbox. The startup
  probe interleaves a TCP-open check with a `callJs("true")` round-trip,
  polled every 200 ms, so the CLI returns as soon as the server is ready.
- **`stopIde`** — kill registered IDE + delete sandbox.
- **`probe [--port N]`** — one `callJs("true")` health check. Intended as
  the canonical pre-flight: `probe` tells callers whether an IDE is
  already running without the noise of `--help` or `java -version`.

### Observation

- **`getStateSummary`** — **default observation command.** Prints the
  focus summary then a compact flat list of every interactive element
  visible right now (buttons, text fields, checkboxes, menus, lists,
  trees, dialogs) with a ready-to-use xpath for each. Designed for use
  after every state-changing action as a cheap "what's in front of me?"
  check.
- **`getState [--no-tree]`** — fallback for unknown layouts. Prints the
  focus summary followed by the full UI component tree (XML); `--no-tree`
  drops the tree and leaves only the focus summary. When
  `Current window: <none>` appears, a native dialog or OS event has stolen
  focus — subsequent clicks and keystrokes will silently go nowhere, and
  the caller should call `focusWindow` before continuing.
- **`screenshot [--output PATH]`** — capture what the robot-server sees
  and save it as a PNG; prints the output path. Last-resort fallback for
  elements that cannot be identified through the component tree (canvas
  renders, icons with no accessible name, visually broken layouts).
  `getStateSummary` remains the default observation.
- **`parseScreenshot [--output PATH] [--threshold N] [--device auto|mps|cpu|cuda] [--debug]`** —
  screenshot + local OmniParser v2 inference in one step. Runs YOLO
  detection + Florence-2 captioning (MPS on Apple Silicon, CPU elsewhere)
  against the captured image and prints a JSON array of detected UI
  elements with screen-absolute pixel coordinates:
  ```json
  [{"id":0,"type":"icon","label":"Settings","x1":10,"y1":20,"x2":50,"y2":60,"cx":30,"cy":40,"w":40,"h":40}, …]
  ```
  The `cx`/`cy` fields feed directly into `clickAt`. First run downloads
  ~500 MB of models to `~/.local/lib/idea-ui-controller/models/`;
  subsequent runs are fully offline. Requires `scripts/install.sh` to
  have been run to set up the Python environment.
- **`clickAt <x> <y>`** — click at an absolute screen pixel via the AWT
  robot. Designed to consume `cx`/`cy` from `parseScreenshot` output so
  callers can interact with elements identified only visually.

### Focus

- **`focusWindow [--title <substring>]`** — bring a window to the front
  and give it focus. Matches the first visible window whose title contains
  `<substring>` (case-insensitive); with no `--title`, focuses the first
  visible window. Prints `Focused: '<title>' (ClassName)` on success,
  exits 1 on no match.

### Generic action / scripting

- **`clickButton (--xpath <xp> | --text <t>) [--timeout-seconds N]`** — find
  and click. Multiple matches → first wins, with a warning on stderr.
- **`runJs --script <js> [--edt]`** — arbitrary `RemoteRobot.runJs`.
- **`callJs --script <js> [--edt]`** — arbitrary `RemoteRobot.callJs`;
  the returned `Serializable` is printed.

### Page-scoped commands

Modeled on the page-object pattern from IdeaVim's `ui-fixtures` (Editor,
IdeaFrame, WelcomeFrame, DialogFixture, SearchEverywhere, ActionMenuFixture,
ActionLinkFixture, Gutter).

**Welcome frame** — `welcomeVerify`, `welcomeNewProject`,
`welcomeNewSolution`, `welcomeMoreActions`, `welcomeImportProject`,
`welcomeHeavyweightPopup`.

**Editor** (all take optional `--file <tab-title>`; omitted → first
`EditorComponentImpl`):
- `editorVerify`, `editorOpen --file X`.
- `editorGetText`, `editorGetSelectedText`, `editorCaretOffset`,
  `editorCaretCount`.
- `editorIsBlockCursor` — `true` for BLOCK/UNDERSCORE shapes (Vim normal
  mode); `false` for BAR (insert) and DEFAULT (no Vim). Uses the IntelliJ
  platform caret API — no IdeaVim reflection.
- `editorCaretShape` — raw shape name (`BLOCK`, `UNDERSCORE`, `BAR`,
  `DEFAULT`) for callers who need to distinguish Vim-normal from Vim-ex
  or OS-specific variants.
- `editorInjectText --text "..."` — write-action replace.
- `editorMoveToLine --line N` — 1-based.

**Keyboard (focused window)**
- `sendKey --key <VK_NAME> [--count N] [--delay-ms M]` — dispatch a named
  key via the AWT robot. `--key` takes a `java.awt.event.KeyEvent.VK_*`
  constant name without the `VK_` prefix (case-insensitive): `ESCAPE`,
  `ENTER`, `TAB`, `BACK_SPACE`, `LEFT`, `F1`, … No modifier support — use
  `sendKeyCombo` for combos.
- `sendKeyCombo --key <VK_NAME> [--modifier MOD]...` — dispatch a modifier
  + key combination. `--modifier` may be repeated: `META`, `CTRL`, `SHIFT`,
  `ALT`. Modifiers press in order, the main key fires, modifiers release
  in reverse.
- `typeText --text "..." [--delay-ms M]` — type a literal string one
  character at a time. Supports `\n`, `\t`, `\r`, `\\` escapes.
- All three target the focused window — callers must ensure the correct
  editor/dialog has focus first.

**Main IDE frame** — `ideVerify`, `ideProjectName`, `ideIsSmart`,
`ideWaitSmart`.

**Dialogs / menus / misc**
- `dialogVerify --title "..."`, `dialogTitle [--title ...]`,
  `dialogClickButton --title X --button Y`.
- `searchEverywhereVerify`.
- `actionMenuVerify --text "..."`, `actionMenuItem --text "..."`.
- `actionLinkClick --xpath "..."`.
- `gutterVerify`.

**Generic widgets**
- `listSelect --xpath <JList-locator> (--index N | --text "...")` — JList
  only; fails on `JTree`.
- `treeSelect --xpath <JTree-locator> (--index N | --text "...")` — select
  a row in a `JTree` by zero-based index or by the last path component's
  `toString()`. Used for the Project tool window tree and any other
  tree-backed widget.
- `textFieldSet --label "..." --text "..."`.
- `checkboxSet --text "..." --checked (true|false)`.
- `actionLinkClickByText --text "..."`.

### High-level flows

- **`projectCreateFromDialog --name X [--location PATH]`** — full
  Welcome → New Project → fill → Create → wait for IdeFrame.
- **`createScratchFile --name X.ext [--language LANG] [--content "..."]`** —
  create + open a scratch file via `ScratchRootType`.

There is intentionally **no** `projectOpen` high-level flow; opening an
existing project composes from the primitives above (`clickButton` on
"Open", `runJs --edt` to set the path — *not* `typeText`, which triggers
autocomplete timeouts — `sendKey --key ENTER`, plus the usual Trust Project
+ onboarding-dismiss steps).

## Build & installation

The binary ships with the skill, not with the user's `PATH`. Consumers
invoke it via the fixed absolute path
`~/.claude/skills/idea-ui-controller/idea-ui-controller`, documented in
SKILL.md, so no shell setup is ever required.

- `./gradlew shadowJar` — `build/libs/idea-ui-controller-fat.jar`.
- `./gradlew executable` — self-executing stub at
  `build/libs/idea-ui-controller`. The stub hardcodes the Java 25 path at
  build time (resolved via the Gradle toolchain) so callers never need to
  set `JAVA_HOME` or `PATH` to invoke it.
- `./gradlew install` — runs `executable` and copies the resulting binary
  into the skill folder at the path above. After any CLI change, a single
  `install` makes the new build live for the agents.

## Non-goals

- No parallel IDE instances. One daemon, one state file.
- No test-framework integration. The CLI drives; callers assert.
- No Robot Framework compatibility. Names collide; protocols do not.
