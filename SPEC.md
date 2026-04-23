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
  installs the robot-server plugin into it, and tears it down on `stopIde`.
- **Exit codes.** `0` = success, non-zero = failure. Every command prints a
  human-readable status line on success.
- **Synchronous.** Every subcommand blocks until its action has completed on
  the IDE side. Callers must not insert their own `sleep`s.

## Runtime requirements

- **Java 25.** The fat-jar stub passes the required `--add-opens` flags.
- **IDE path.** On macOS, the `.app` bundle directory (e.g.
  `IntelliJ IDEA.app`). On Linux/Windows, the unpacked distribution dir.

## Companion skill

The CLI is consumed by a Claude Code skill at
`.claude/skills/idea-ui-controller/SKILL.md` which teaches an agent how to
use every command below. When the CLI surface changes, the skill changes in
the same commit — the agent has no other source of truth.

## Command surface

### Daemon lifecycle

- **`runIde <idePath> [--port N] [--timeout-seconds N]`** — launch, wait for
  robot-server, register state. Refuses to start a second IDE.
- **`stopIde`** — kill registered IDE + delete sandbox. Tolerates stale state.
- **`probe [--port N]`** — one `callJs("true")` health check.

### Observation

- **`getState`** — print the full UI component tree (XML). Used to discover
  xpaths by grepping for `accessiblename`, `class`, or `text`.

### Generic action / scripting

- **`clickButton (--xpath <xp> | --text <t>) [--timeout-seconds N]`** — find
  a component and click it. `--text` resolves to a disjunction over
  `@text`, `@accessiblename`, and `@visible_text`. Multiple matches → first
  wins, with a warning on stderr.
- **`runJs --script <js> [--edt]`** — arbitrary `RemoteRobot.runJs` (no
  return value). `--edt` runs the script on the AWT event dispatch thread.
- **`callJs --script <js> [--edt]`** — arbitrary `RemoteRobot.callJs`; the
  returned `Serializable` is printed.

### Page-scoped commands

Modeled on the page-object pattern from IdeaVim's `ui-fixtures` (Editor,
IdeaFrame, WelcomeFrame, DialogFixture, SearchEverywhere, ActionMenuFixture,
ActionLinkFixture, Gutter).

**Welcome frame** — `welcomeVerify`, `welcomeNewProject`,
`welcomeNewSolution`, `welcomeMoreActions`, `welcomeImportProject`,
`welcomeHeavyweightPopup`.

**Editor** (all take optional `--file <tab-title>`; omitted → first
`EditorComponentImpl`):
- `editorVerify`, `editorOpen --file X` (clicks the tab).
- `editorGetText`, `editorGetSelectedText`, `editorCaretOffset`,
  `editorCaretCount`, `editorIsBlockCursor`.
- `editorInjectText --text "..."` — replaces the document via a write action.
- `editorMoveToLine --line N` — 1-based.

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
- `listSelect --xpath <JList-locator> (--index N | --text "...")`.
- `textFieldSet --label "..." --text "..."` — sets a `JTextComponent` whose
  `labelFor` is the given `JLabel`.
- `checkboxSet --text "..." --checked (true|false)` — clicks only if the
  current state differs; the value is mandatory.
- `actionLinkClickByText --text "..."` — click an `ActionLink` by its
  visible text (used to dismiss onboarding panels like
  "Meet the Islands Theme").

### High-level flows

- **`projectCreateFromDialog --name X [--location PATH]`** — runs
  Welcome → New Project → fill Name/Location → Create → wait for IdeFrame.
  Post-return state: the project may still be indexing (call `ideWaitSmart`
  if needed) and onboarding panels may appear.
- **`createScratchFile --name X.ext [--language LANG] [--content "..."]`** —
  create and open a scratch file via `ScratchRootType`. Requires an open
  project. `--language` is an IntelliJ Language ID (e.g. `kotlin`, `JAVA`,
  `Python`); defaults to plain text. Scratches live under the IDE config
  directory (the sandbox's `config*/scratches/`), not under the project.

## Installation

`scripts/install.sh` builds the executable and places it on a predictable
path so agents can invoke it by name.

## Build

- `./gradlew shadowJar` — fat jar at `build/libs/idea-ui-controller-fat.jar`.
- `./gradlew executable` — self-executing stub at `build/libs/idea-ui-controller`.

## Non-goals

- No parallel IDE instances. One daemon at a time, one state file.
- No test-framework integration. The CLI drives the IDE; callers assert.
- No Robot Framework compatibility. Names collide; protocols do not.
