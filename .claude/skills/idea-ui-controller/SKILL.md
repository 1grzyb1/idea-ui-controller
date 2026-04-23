---
name: idea-ui-controller
description: Autonomously drive a running JetBrains IDE (IntelliJ, Rider, PyCharm…) from the command line — launch the IDE with a UI-automation plugin, then click buttons, read the component tree, get/set editor text, open dialogs, or run arbitrary introspection scripts. Use when a task requires interacting with a real IDE window (verifying a plugin's UI, exercising actions end-to-end, exploring IDE behavior, scripting a reproducer). Reads like a CLI — one invocation per step. Built on JetBrains' intellij-ui-test-robot (https://github.com/JetBrains/intellij-ui-test-robot).
---

# idea-ui-controller

A CLI that launches a JetBrains IDE with the `robot-server` plugin bolted on and then drives it over RPC. Use it to verify plugin UI, explore unfamiliar IDE behavior, or produce deterministic reproducers.

The underlying library is JetBrains' **[intellij-ui-test-robot](https://github.com/JetBrains/intellij-ui-test-robot)** — all locator syntax (`byXpath`), fixture concepts, and `callJs` / `runJs` semantics come from there. It is NOT the Python "Robot Framework" at robotframework.org — unrelated project, different conventions.

## Prerequisites — verify before any run

1. **Java 25** must be the active JVM. The CLI won't start on older JDKs.
   ```sh
   java -version   # must show 25.x
   # if not:
   export JAVA_HOME=/Users/wkeska/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home
   export PATH="$JAVA_HOME/bin:$PATH"
   ```

2. **`idea-ui-controller` on PATH.** It's a single self-extracting shell/jar produced by the project's `./gradlew executable` task (at `~/IdeaProjects/idea-ui-controller/build/libs/idea-ui-controller`). Add that directory to PATH or symlink it into a PATH dir.
   ```sh
   command -v idea-ui-controller   # must resolve
   idea-ui-controller --help       # must print the command list
   ```

3. **A built IDE to target.** The IDE path you pass to `runIde` must be a real IntelliJ-family `.app` bundle on macOS (directory ending in `.app`) or the equivalent unpacked distribution directory on Linux/Windows. Example of the correct shape:
   ```
   /Users/wkeska/IdeaProjects/ideavim/out/ide-tests/cache/builds/IU-261.23567.71/IntelliJ IDEA.app
   ```
   Note the trailing `.app` — you pass the bundle directory itself, not its parent and not a binary inside it.

   **Discover the real path before launching.** The Gradle cache sometimes prefixes the build dir (e.g. `___IU-261.23567.71/` while a toolchain lock is held). Always run `ls <project>/out/ide-tests/cache/builds/` and use the exact directory name you see.

4. **Plugin-under-test must be prepared.** If you're driving a plugin you're developing (the common case), the IDE referenced above needs to already have the plugin compiled and installed into a sandbox. In projects that use the `org.jetbrains.intellij.platform` Gradle plugin, run once before invoking the skill:
   ```sh
   ./gradlew prepareSandbox   # builds target IDE + installs plugin into a sandbox
   # or
   ./gradlew buildPlugin      # produces a distributable .zip
   ```
   The path under `out/ide-tests/cache/builds/...` appears after `prepareSandbox` succeeds.

## Startup time

- **First `runIde` per machine: ~60 seconds.** The CLI downloads `robot-server-plugin-*.zip` (~2 MB) from JetBrains' public Maven on the first call and then cold-starts the IDE.
- **Subsequent runs on the same machine: ~10 seconds.** The plugin is cached; only IDE cold start matters.
- The IDE stays running as a daemon across CLI invocations. Only `stopIde` (or killing the process) tears it down. State is kept at `~/.idea-ui-controller/state.properties`.

## Command catalog

**Daemon lifecycle**
- `runIde <path-to-.app>` — launch IDE, wait for robot-server, register state, exit.
- `stopIde` — kill registered IDE + clean sandbox.
- `probe` — one `callJs("true")` round-trip; quick health check.

**Generic**
- `getState` — print a **focus summary** (current window + list of other visible windows) followed by the full UI component tree (XML). Use the summary to answer "where am I?"; pipe the XML to `grep` to discover xpaths. Add `--no-tree` to get only the summary. Expected shape:
  ```
  Current window: 'New Project' (MyDialog)
  Other windows: ['Welcome to IntelliJ IDEA' (FlatWelcomeFrame)]
  --- component tree ---
  <html>…
  ```
- `clickButton --xpath=<xp>` (preferred) or `--text=<visible text>` — find and click.
- `runJs --script "..." [--edt]` — arbitrary robot runJs (no return).
- `callJs --script "..." [--edt]` — arbitrary robot callJs (prints Serializable result).

**Welcome frame**
- `welcomeVerify`
- `welcomeNewProject` · `welcomeNewSolution` · `welcomeMoreActions` · `welcomeImportProject` · `welcomeHeavyweightPopup`

**Editor** (all take optional `--file <tab-title>`; omit to target the first `EditorComponentImpl`)
- `editorVerify` · `editorOpen --file X` (clicks the tab)
- `editorGetText` · `editorGetSelectedText` · `editorCaretOffset` · `editorCaretCount` · `editorIsBlockCursor`
- `editorInjectText --text "..."` (replaces document via write action)
- `editorMoveToLine --line N` (1-based)

**Main IDE frame**
- `ideVerify` · `ideProjectName` · `ideIsSmart` · `ideWaitSmart`

**Dialogs / menus / misc**
- `dialogVerify --title "..."` · `dialogTitle [--title ...]`
- `dialogClickButton --title X --button Y` — click a named button inside a titled modal.
- `searchEverywhereVerify`
- `actionMenuVerify --text "..."` · `actionMenuItem --text "..."`
- `actionLinkClick --xpath "..."`
- `gutterVerify`

**Widgets (generic)**
- `listSelect --xpath "<locator for the JList>" (--index N | --text "...")` — select a JBList/JList row.
- `textFieldSet --label "..." --text "..."` — set a JTextComponent whose `labelFor` is the given JLabel.
- `checkboxSet --text "..." --checked true --checked false` — click a JCheckBox only if its current state differs. `--checked` requires an explicit value: `true` or `false`.
- `actionLinkClickByText --text "Skip"` — click an `ActionLink` by its visible text. Handy for dismissing onboarding panels.

**High-level flows**
- `projectCreateFromDialog --name X [--location PATH]` — runs the full Welcome → New Project → fill Name/Location → Create → wait for IdeFrame sequence. After it returns, the project may still be indexing (call `ideWaitSmart` if you need smart mode) and onboarding panels (e.g. "Meet the Islands Theme") may appear — dismiss with `actionLinkClickByText --text "Skip"`.
- `createScratchFile --name X.ext [--language LANG] [--content "..."]` — creates a scratch file via `ScratchRootType` and opens it. Requires a project to be open (`ideVerify`). `--language` is an IntelliJ Language ID (e.g. `kotlin`, `JAVA`, `Python`); defaults to plain text. Note: scratches don't live under the project directory — they're stored under the IDE's config dir (in our case the CLI's sandbox: `<sandbox>/config*/scratches/`).

## Example flow — open the New Project dialog

```sh
IDE="/Users/wkeska/IdeaProjects/ideavim/out/ide-tests/cache/builds/IU-261.23567.71/IntelliJ IDEA.app"
idea-ui-controller runIde "$IDE"              # ~60s cold, ~10s warm
idea-ui-controller welcomeVerify               # → "Welcome frame present."
idea-ui-controller welcomeNewProject           # clicks the New Project button
idea-ui-controller dialogVerify --title "New Project"
idea-ui-controller stopIde
```

## Example flow — read editor text after an action

```sh
# (IDE already running with a project open)
idea-ui-controller editorOpen --file "Main.kt"
before=$(idea-ui-controller editorGetText --file "Main.kt")
# ... trigger an action via actionMenuItem, a keystroke via runJs, etc. ...
after=$(idea-ui-controller editorGetText --file "Main.kt")
diff <(echo "$before") <(echo "$after")
```

## Example flow — exploring an unknown UI

When the xpath for a control isn't known, dump the tree and grep for an anchor like `accessiblename` or `text`:

```sh
idea-ui-controller getState > /tmp/ui.xml
grep -n "accessiblename=\"[^\"]*Settings" /tmp/ui.xml | head
# pick an xpath from surrounding attrs:
idea-ui-controller clickButton --xpath "//div[@accessiblename='Settings' and @class='JButton']"
```

## Example flow — IdeaVim-specific introspection via callJs

```sh
idea-ui-controller editorIsBlockCursor --file "Main.kt"
idea-ui-controller callJs --script "com.maddyhome.idea.vim.VimPlugin.getState().toString()"
```

Any script valid as a `RemoteRobot.callJs` body works — full Java reflection is available via Rhino. See the intellij-ui-test-robot docs for the sandbox.

## Tips for agent use

- **Run `getState` between every UI action.** The live UI is not deterministic across versions or between runs — dialogs shift, focus moves, indexing kicks in. After every `clickButton` / `welcome*` / `actionMenuItem` / `dialogClickButton` / any state-changing call, re-observe the UI (`getState`, or a narrower probe like `dialogTitle` / `ideVerify` / `editorVerify`) before deciding the next step. Chaining actions blind is the fastest way to wedge.
- **Multiple matches.** `clickButton --text` can match several components (e.g. a `JButton` and its child `JBLabel` both carry the label text). The CLI clicks the first match and prints a warning. When the action doesn't fire, add `--xpath` with a `@class` predicate: `"//div[@accessiblename='New Project' and @class='JButton']"`.
- **Quoting paths with spaces.** Always quote the `.app` path — it contains a space (`IntelliJ IDEA.app`).
- **Idempotency / stale state recovery.** `runIde` refuses to start a second IDE if one is registered. If a command errors with a connection failure (`ConnectException`, socket timeout), the daemon may be dead while `state.properties` still points at it. Recover with `idea-ui-controller stopIde` (it tolerates a stale state) then `runIde` again.
- **State file.** Read/write at `~/.idea-ui-controller/state.properties`. Contains `pid`, `port`, `sandbox`.
- **`runJs` + EDT footgun.** Never call long-running or EDT-blocking operations directly (`ProjectUtil.openOrImport`, full project indexing triggers, modal dialogs spun on the EDT) from a `runJs` with `--edt` — they starve the robot-server and wedge the IDE. Wrap them in `ApplicationManager.getApplication().invokeLater(...)` or use a background task. `WriteAction.run(...)` inside `invokeAndWait(...)` is fine for short write-path edits (that's what `createScratchFile` / `editorInjectText` do).
- **Do NOT `sleep`.** The CLI is synchronous — every subcommand blocks until it finishes. External waits are pure waste.

## When NOT to use

- If the task is pure unit/integration testing — use the project's test harness.
- If the user wants to read static source — use normal file tools.
- If no IDE is required (e.g. running a Gradle task, grepping code) — just run that task directly.

## Reference

- intellij-ui-test-robot (underlying library): https://github.com/JetBrains/intellij-ui-test-robot
- Fixture catalog mirrors IdeaVim's `ui-fixtures/src/testFixtures/kotlin/ui/pages/` — `Editor.kt`, `IdeaFrame.kt`, `WelcomeFrame.kt`, `DialogFixture.kt`, `SearchEverywhere.kt`, `ActionMenuFixture.kt`, `ActionLinkFixture.java`, `Gutter.kt`.
- Source of this tool: `~/IdeaProjects/idea-ui-controller/`.
