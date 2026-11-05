---
name: idea-ui-controller
description: Autonomously drive a running JetBrains IDE (IntelliJ, Rider, PyCharm…) from the command line — launch the IDE with a UI-automation plugin, then click buttons, read the component tree, get/set editor text, open dialogs, or run arbitrary introspection scripts. Use when a task requires interacting with a real IDE window (verifying a plugin's UI, exercising actions end-to-end, exploring IDE behavior, scripting a reproducer). Reads like a CLI — one invocation per step. Built on JetBrains' intellij-ui-test-robot (https://github.com/JetBrains/intellij-ui-test-robot).
---

# idea-ui-controller

A CLI that launches a JetBrains IDE with the `robot-server` plugin bolted on and then drives it over RPC. Use it to verify plugin UI, explore unfamiliar IDE behavior, or produce deterministic reproducers.

The underlying library is JetBrains' **[intellij-ui-test-robot](https://github.com/JetBrains/intellij-ui-test-robot)** — all locator syntax (`byXpath`), fixture concepts, and `callJs` / `runJs` semantics come from there. It is NOT the Python "Robot Framework" at robotframework.org — unrelated project, different conventions.

## Prerequisites — verify before any run

1. **The binary ships with this skill.** The `install` Gradle task places a self-contained fat-jar executable at a fixed absolute path next to this `SKILL.md`:
   ```
   ~/.claude/skills/idea-ui-controller/idea-ui-controller
   ```
   No `PATH` setup, no `JAVA_HOME`, no env vars — the stub hardcodes the Java 25 path at build time. Invoke it directly by absolute path; all examples below do the same. Verify readiness with one round-trip:
   ```sh
   ~/.claude/skills/idea-ui-controller/idea-ui-controller probe   # OK: true → IDE already running; FAIL → need runIde
   ```
   Do **not** run `--help` or `java -version` as a pre-flight — they add noise and `probe` already tells you everything you need.

   If the binary is missing or stale, (re)install the binary and this skill:
   ```sh
   gh api -H 'Accept: application/vnd.github.raw' repos/1grzyb1/idea-ui-controller/contents/scripts/quick-install.sh | bash
   ```
   From a clone: `./scripts/quick-install.sh` (or `./gradlew install` for just the binary).

2. **A built IDE to target.** The IDE path you pass to `runIde` must be a real IntelliJ-family `.app` bundle on macOS (directory ending in `.app`) or the equivalent unpacked distribution directory on Linux/Windows. Example of the correct shape:
   ```
   /Users/wkeska/IdeaProjects/ideavim/out/ide-tests/cache/builds/IU-261.23567.71/IntelliJ IDEA.app
   ```
   Note the trailing `.app` — you pass the bundle directory itself, not its parent and not a binary inside it.

   **Discover the real path before launching.** The Gradle cache sometimes prefixes the build dir (e.g. `___IU-261.23567.71/` while a toolchain lock is held). Always run `ls <project>/out/ide-tests/cache/builds/` and use the exact directory name you see.

3. **Plugin-under-test must be prepared.** If you're driving a plugin you're developing (the common case), the IDE referenced above needs to already have the plugin compiled and installed into a sandbox. In projects that use the `org.jetbrains.intellij.platform` Gradle plugin, run once before invoking the skill:
   ```sh
   ./gradlew prepareSandbox   # builds target IDE + installs plugin into a sandbox
   # or
   ./gradlew buildPlugin      # produces a distributable .zip
   ```
   The path under `out/ide-tests/cache/builds/...` appears after `prepareSandbox` succeeds.

## Startup time

- **First `runIde` per machine: ~60 seconds.** The CLI downloads `robot-server-plugin-*.zip` (~2 MB) from JetBrains' public Maven on the first call and then cold-starts the IDE.
- **Subsequent runs on the same machine: ~10 seconds.** The plugin is cached; only IDE cold start matters.
- The IDE stays running as a daemon across CLI invocations. Only `stopIde` (or killing the process) tears it down. State is kept at `~/.idea-ui-controller/instances/<instance>.properties`.

## Several IDEs at once (`--instance NAME`)

Several agents can each drive their own IDE at the same time. Every IDE is a named **instance** with its own port,
sandbox and (with `--docker`) container `idea-ui-controller-<name>`.

- **When other agents may be running IDEs too, always pass `--instance <unique name>`** (e.g. your scenario slug) to
  `runIde` **and to every later command**, before or after the subcommand: `$U --instance vim-undo editorState`. Or
  set `IUC_INSTANCE=vim-undo` in the same shell command. Easiest: `U="$HOME/.claude/skills/idea-ui-controller/idea-ui-controller --instance vim-undo"`.
- Without `--instance`, `runIde` names it `default` (or `ide2`, `ide3`… if taken) and picks the first free port from 8082;
  other commands use the only running IDE and fail with "2 IDEs are running (a, b): pass --instance" when there are more.
  So a command never silently drives someone else's IDE.
- `instances` lists them (name, port, up/starting/dead, container, sandbox). `stopIde` stops only the selected one;
  `stopIde --all` stops every one, also other agents' — don't use it unless told to.
- Parallel IDEs need `--docker`: two host IDEs would fight over the real keyboard and mouse, so `runIde` refuses a
  second host one. Each Docker IDE takes ~2–4 GB of the Docker VM's memory.

## Sandbox is fresh on every `runIde`

Each `runIde` call creates a **new** temp sandbox and installs only the plugins passed on *that* command. It does NOT carry plugins forward from a prior daemon. Consequences:

- If the plugin you're testing (e.g. IdeaVim) lived in the last sandbox only because you passed `--plugin` then, you MUST pass `--plugin` again on every relaunch — otherwise the plugin is absent and any JS that references its classes fails with `TypeError: ... is not a function, it is "object"`.
- Pass an unpacked plugin directory (the one produced under `<your-plugin-project>/build/idea-sandbox/.../plugins_runIdeForUiTests/<PluginName>`) or a built `.zip`. Directory form is auto-zipped for you.
- If the daemon is already alive (`probe` returns `OK: true`), prefer reusing it over `stopIde` + `runIde` — you skip the 10+ seconds of cold-start and avoid the plugin-install step entirely.

## Docker mode (`runIde --docker`)

Without `--docker` the AWT robot sends real OS key and mouse events: the IDE window has to be in front and the user can't
use the machine meanwhile. With `--docker` everything happens on Xvfb inside a container; every other command works
unchanged over `127.0.0.1:<port>`.

- Needs Docker Desktop running. The CLI finds `docker` on `PATH` or in `~/.docker/bin`.
- Pass the macOS `.app` as usual: the CLI reads its product code and build number and uses the Linux build of the same
  version (aarch64 or x86_64, whatever Docker runs), downloaded once (~1.5 GB, streamed, the archive isn't kept) and
  unpacked to `~/.idea-ui-controller/linux-ides` (3–7 GB each). Only the 3 most recently used builds are kept
  (`IDEA_UI_CONTROLLER_IDE_CACHE=N` to change); `runIde --docker` deletes older ones and sandboxes left by IDEs that
  weren't stopped. `ideCache` lists what's cached. A Linux IDE directory works too, and so does a macOS IDE unpacked by
  the IntelliJ Platform Gradle plugin (a `Contents` directory without the `.app` around it, e.g.
  `~/.gradle/caches/*/transforms/*/transformed/idea-2026.2.3-aarch64`).
- Instead of a path you can pass a build to download: `runIde --docker IU-263.7307.44`, or `IU-eap` for the newest EAP
  (`https://data.services.jetbrains.com/products/releases?code=IIU&type=eap&latest=true`). An EAP build expires ~30 days
  after release: it shows "EAP Build Expired" and exits, and `runIde` then says the logs mention an expired build. Use
  `IU-eap`. A downloaded Linux IDE dir (`~/.idea-ui-controller/linux-ides/IU-<build>-aarch64`) can be passed directly.
- A fresh EAP sandbox opens a modal "Log in to IntelliJ IDEA" dialog first. Log in with `jbaLogin` (see "EAP login").
- The runtime image `idea-ui-controller-xvfb` is built from `src/main/resources/docker/` on the first run (~1 min),
  cached after.
- Paths are the same inside and outside the container: the sandbox (`~/.idea-ui-controller/sandboxes/...`), the IDE and
  each `--mount DIR` are mounted 1:1. **A project the IDE should open must be passed as `--mount`** (e.g.
  `--mount /tmp/vimtest`), otherwise it doesn't exist in the container. Files edited in the IDE are the host files.
- It's Linux: the default keymap is the Linux one (Ctrl, not Cmd; e.g. Ctrl+F is Find), the window title says `(ROOT)`.
  Bugs specific to macOS (Cmd shortcuts, native macOS focus) still need the host mode.
- `screenshot` shows the 1920x1080 virtual screen. `idea.log` is in `<sandbox>/log*/idea.log` as usual; the IDE's raw
  output (`<sandbox>/ide-output.log`) is the container output.

## Split mode (`runIde --docker --split PROJECT`)

Remote Development split mode in one container: the IDE runs as a backend (`bin/<ide>.sh serverMode PROJECT`), and the
JetBrains Client bundled with the IDE (`bin/jetbrains_client.sh thinClient <join link>`) connects to it. The
robot-server runs in the **client**, so every command drives the frontend; the backend has no robot.

```sh
runIde --docker --split /tmp/proj/ConsoleApp.sln --timeout-seconds 900 --plugin <plugin dir> ~/.idea-ui-controller/linux-ides/RD-262.11879.110-aarch64
```

- `--plugin` goes into both the backend and the client; the robot-server only into the client.
- PROJECT is mounted automatically (its directory, when it's a file). Rider needs a `.sln`, it refuses a plain folder
  ("Unable to open file").
- A Rider/CLion backend only works on Linux, so split mode is Docker-only. The macOS Rider has no remote dev host at all.
- Sandbox layout: `<sandbox>/backend/` and `<sandbox>/client/` (each with `config*`, `system*`, `log*`). The backend's
  output, with the join link, is `<sandbox>/backend/ide-output.log`; the client's is `<sandbox>/ide-output.log`.
- `callJs`/`runJs` run in the client (`PlatformUtils.getPlatformPrefix()` is `JetBrainsClient`). Don't change documents
  from scripts there (`document.setText` in a write command): the client and backend get out of sync, the text comes
  back and undo stops working. Edit through typed keys instead.
- A fresh Rider sandbox shows the license dialog: `clickButton --text "Start trial"`, then
  `clickButton --text "Start Free 30-Day Trial"`, then `clickButton --text "Close"`. No account needed.
- Opening a file: there's no local VFS path for project files in the client. Use Go to File:
  `sendKeyCombo --key N --modifier CTRL --modifier SHIFT`, `typeText --text Program.cs`, `sendKey --key ENTER`. Only
  for a file that exists: otherwise the popup closes and the typed text goes into the editor.
- Rider in split mode is heavy: the backend, ReSharper and the client share the Docker VM's memory (~8 GB by default).
  The backend can freeze (`threadDumps-freeze-*` in `<sandbox>/backend/log*/`) or Docker Desktop itself can die
  (`docker.sock` gone, `runIde` state stale). After a run, `screenshot` and check there's no "No connection to IDE
  Backend" banner in the editor: results typed after the connection dropped are garbage, the client keeps its own
  copy of the text and undo does nothing. Start a fresh `runIde` per scenario rather than running many in one session.
- Reading the caret or selection needs the EDT: `callJs --edt`, otherwise "Access to Editor models ... is allowed
  either from EDT, or under read action".

## Command catalog

**Daemon lifecycle**
- `runIde <path-to-.app>` — launch IDE, wait for robot-server, register state, exit. The IDE's own stdout/stderr go to `<sandbox>/ide-output.log` (printed by `runIde`), not to the CLI's output, so `runIde` returns even when its output is piped or captured. The IDE's `idea.log` is in `<sandbox>/log*/idea.log`.
- `runIde --docker [--mount DIR]... <path-to-.app | linux-ide-dir | IU-<build> | IU-eap>` — **preferred on macOS.** Runs the Linux build of the same IDE in a Docker container (`idea-ui-controller`) on a virtual display (Xvfb), so typing and clicking never touch the host keyboard, mouse or focus. See "Docker mode" below.
- `runIde --docker --split PROJECT ...` — Remote Development split mode: backend on PROJECT plus the bundled JetBrains Client, driven through the client. See "Split mode" above.
- `runIde --instance NAME [--restart] [--port N] ...` — register the IDE as NAME (see "Several IDEs at once"); `--restart` stops a running NAME first instead of failing; the port defaults to the first free one from 8082.
- `stopIde [--all]` — kill the selected IDE + clean its sandbox (in Docker mode also removes its container). `--all`: every registered IDE.
- `instances` — list registered IDEs: name, port, `up`/`starting`/`dead`, container (or `host`), sandbox.
- `probe` — one `callJs("true")` round-trip; quick health check.
- `ideCache [--keep N]` — list the Linux IDEs cached for `--docker` (size, last use, path), most recently used first. `--keep N` deletes all but the N most recently used, plus leftover archives, unfinished unpacks and sandboxes; refuses while an IDE runs.

**Generic**
- `getStateSummary` — **default observation command.** Prints the focus summary then a compact list of every interactive element (buttons, fields, checkboxes, menus, lists, trees, dialogs) with ready-to-use xpaths. Use this after every state-changing action. Pipe through `grep` to narrow further. Falls back to `getState` when you need the raw tree.
- `getState` — print a **focus summary** (current window + list of other visible windows) followed by the full UI component tree (XML). Use as a fallback when `getStateSummary` isn't sufficient. Add `--no-tree` to get only the focus summary. Expected shape:
  ```
  Current window: 'New Project' (MyDialog)
  Other windows: ['Welcome to IntelliJ IDEA' (FlatWelcomeFrame)]
  --- component tree ---
  <html>…
  ```
- `screenshot [--output PATH]` — capture what the robot-server sees and save it as a PNG; prints the output path. **Last-resort fallback only** — use `getStateSummary` for all normal observation. Only reach for this when a visual element cannot be identified through the component tree (e.g. a canvas render, an icon with no accessible name, diagnosing a visually broken layout). After running it, read the file at the printed path to interpret it visually.
- `parseScreenshot [--output PATH] [--threshold N] [--device auto|mps|cpu|cuda] [--debug]` — screenshot + local OmniParser v2 inference in one step. Takes a screenshot, runs YOLO detection + Florence-2 captioning locally (MPS on Apple Silicon, CPU elsewhere), and prints a JSON array of detected UI elements:
  ```json
  [{"id":0,"type":"icon","label":"Settings","x1":10,"y1":20,"x2":50,"y2":60,"cx":30,"cy":40,"w":40,"h":40}, ...]
  ```
  All coordinates are screen-absolute pixels (full-screen capture). Pass `cx`/`cy` directly to `clickAt`. First run downloads ~500 MB of models (YOLO + Florence-2-base) to `~/.local/lib/idea-ui-controller/models/`; subsequent runs are fully offline. Requires `scripts/install.sh` to have been run. Use when `getStateSummary` can't identify an element (visual-only icons, canvas regions).
- `clickAt <x> <y>` — click at an absolute pixel coordinate via the AWT robot. Use `cx`/`cy` from `parseScreenshot` output to interact with elements that were identified visually. Example: `idea-ui-controller clickAt 150 300`.
- `clickButton --xpath=<xp>` (preferred) or `--text=<visible text>` — find and click.
- `focusWindow [--title <substring>]` — bring a window to the front and give it focus. Matches the first visible window whose title contains `<substring>` (case-insensitive). Omit `--title` to focus the first visible window. Prints `Focused: '<title>' (ClassName)` on success; exits 1 if no match. Useful when `getState` shows `Current window: <none>` because a native dialog stole focus.
- `runJs --script "..." [--edt]` — arbitrary robot runJs (no return).
- `callJs --script "..." [--edt]` — arbitrary robot callJs (prints Serializable result). See "Scripts" below.
- `linkClick --text "..." [--href] [--timeout-seconds N]` — click a hyperlink by its text: an `<a>` inside an HTML
  `JEditorPane`/label (e.g. "copy the link"), an `ActionLink`, `LinkLabel` or `HyperlinkLabel`. Tags stripped, HTML
  entities decoded (`&larr; Back` = `← Back`), whitespace collapsed; exact match, else the only link containing the
  text. `--href` prints the link and its URL without clicking. On no match it lists the links on screen.
- `clipboard [--set TEXT]` — print the IDE clipboard (`CopyPasteManager`), or set it.
- `focusedComponent` — the focus owner: class, window, text, empty text, accessible name and **all client properties**
  (validation errors show up as `JComponent.outline = error` + `Error.text = ...`).
- `traceActions --start | --dump | --stop` — record executed actions (id, place, key stroke, result) and shortcuts with
  their candidate actions. `--dump` prints and clears.

**Welcome frame**
- `welcomeVerify`
- `welcomeNewProject` · `welcomeNewSolution` · `welcomeMoreActions` · `welcomeImportProject` · `welcomeHeavyweightPopup`

**Editor** (all take optional `--file <tab-title>`; omit to target the first `EditorComponentImpl`)
- `editorVerify` · `editorOpen --file X` (clicks the tab)
- `editorGetText` · `editorGetSelectedText` · `editorCaretOffset` · `editorCaretCount`
- `editorIsBlockCursor` — true for BLOCK/UNDERSCORE shape (Vim normal mode); false for BAR (insert) and DEFAULT (no Vim). Uses the IntelliJ platform caret API — no IdeaVim reflection.
- `editorCaretShape` — raw shape name: `BLOCK`, `UNDERSCORE`, `BAR`, or `DEFAULT`. Use when you need to distinguish Vim-normal (BLOCK) from Vim-ex or OS-specific variants.
- `editorInjectText --text "..."` (replaces document via write action)
- `editorMoveToLine --line N` (1-based)

**Keyboard (focused window)**
- `sendKey --key <VK_NAME> [--count N] [--delay-ms M]` — dispatch a named key via the AWT robot. `--key` takes a `java.awt.event.KeyEvent.VK_*` constant name **without the `VK_` prefix** (case-insensitive): `ESCAPE`, `ENTER`, `TAB`, `BACK_SPACE`, `LEFT`, `F1`, etc. `--count` repeats with `--delay-ms` between presses. Use for named keys and for Vim commands like `Esc`. **No modifier support** — for combos use `sendKeyCombo`.
- `sendKeyCombo --key <VK_NAME> [--modifier MOD]...` — dispatch a modifier+key combination. `--modifier` may be repeated: `META` (alias `CMD`), `CONTROL` (alias `CTRL`), `SHIFT`, `ALT` (alias `OPTION`). Examples: `--key S --modifier META` (Cmd+S), `--key Z --modifier META --modifier SHIFT` (Cmd+Shift+Z). Modifiers are pressed in order, main key fires, modifiers release in reverse order.
- `typeText --text "..." [--delay-ms M] [--set]` — type a literal string one char at a time (~25 ms/char, sent in
  ~3 s chunks, returns only after the last key arrived, so the next command can't race it). `--set` does `setText` on
  the focused `JTextComponent` on the EDT instead — use it for tokens, paths and anything long. Supports `\n`, `\t`, `\r`, `\\` escapes; `\n` and `\r` map to VK_ENTER, `\t` to VK_TAB. Use for Vim commands composed of printable chars (`dd`, `yyp`, `gg`), insert-mode content entry, etc. **Footgun — do not use in autocomplete/file-chooser fields:** see Known footguns below.
- All three target the **focused** window — ensure the right editor/dialog has focus first (`editorOpen` / `clickButton` as needed).

**Main IDE frame**
- `ideVerify` · `ideProjectName` · `ideIsSmart` · `ideWaitSmart` — the smart-mode checks look at all open projects off the EDT, so they also work while a modal dialog is open.

**Dialogs / menus / misc**
- `dialogVerify --title "..."` · `dialogTitle [--title ...]`
- `dialogClickButton --title X --button Y` — click a named button inside a titled modal.
- `searchEverywhereVerify`
- `actionMenuVerify --text "..."` · `actionMenuItem --text "..."`
- `actionLinkClick --xpath "..."`
- `gutterVerify`

**Widgets (generic)**
- `listSelect --xpath "<locator for the JList>" (--index N | --text "...")` — select a JBList/JList row. **JList only** — fails on `JTree` components (e.g. the Project tool window tree); use `treeSelect` for those.
- `treeSelect --xpath "<locator for the JTree>" (--index N | --text "...")` — select a row in a JTree by zero-based index or by its last path component's `toString()`. Use for the Project tool window tree and any other `JTree`-backed widget. After selecting a node, trigger actions on it via keyboard shortcut (e.g. `sendKeyCombo --key INSERT --modifier ALT` for Alt+Insert / New).
- `textFieldSet --label "..." --text "..."` — set a JTextComponent whose `labelFor` is the given JLabel.
- `checkboxSet --text "..." --checked true --checked false` — click a JCheckBox only if its current state differs. `--checked` requires an explicit value: `true` or `false`.
- `actionLinkClickByText --text "Skip"` — same as `linkClick`. Handy for dismissing onboarding panels.

**High-level flows**
- `projectCreateFromDialog --name X [--location PATH]` — runs the full Welcome → New Project → fill Name/Location → Create → wait for IdeFrame sequence. After it returns, the project may still be indexing (call `ideWaitSmart` if you need smart mode) and onboarding panels (e.g. "Meet the Islands Theme") may appear — dismiss with `actionLinkClickByText --text "Skip"`.

- `jbaLogin [--token T | --token-file F | --token -]` — EAP login, see below.

**Opening an existing project** (no dedicated command yet — do these steps manually):
```sh
~/.claude/skills/idea-ui-controller/idea-ui-controller clickButton --xpath "//div[@accessiblename='Open' and @class='JButton']"
# set the path without keystrokes — plain typeText triggers autocomplete on every char
~/.claude/skills/idea-ui-controller/idea-ui-controller typeText --set --text '/your/project/path'
~/.claude/skills/idea-ui-controller/idea-ui-controller sendKey --key ENTER
# Trust Project dialog always appears on first open of a project in the sandbox
~/.claude/skills/idea-ui-controller/idea-ui-controller clickButton --xpath "//div[@accessiblename='Trust Project' and @class='JButton']"
# "Meet the Islands Theme" onboarding panel reliably appears after every project open — always dismiss it
~/.claude/skills/idea-ui-controller/idea-ui-controller actionLinkClickByText --text "Skip"
~/.claude/skills/idea-ui-controller/idea-ui-controller ideWaitSmart
```
- `createScratchFile --name X.ext [--language LANG] [--content "..."]` — creates a scratch file via `ScratchRootType` and opens it. Requires a project to be open (`ideVerify`). `--language` is an IntelliJ Language ID (e.g. `kotlin`, `JAVA`, `Python`); defaults to plain text. Note: scratches don't live under the project directory — they're stored under the IDE's config dir (in our case the CLI's sandbox: `<sandbox>/config*/scratches/`).

## EAP login (fresh sandbox)

The modal "Log in to IntelliJ IDEA" blocks everything. There is no browser in Docker, so:

```sh
U=~/.claude/skills/idea-ui-controller/idea-ui-controller
$U jbaLogin                          # clicks "Log in to JetBrains Account" -> "Troubles?", prints the OAuth URL (%20-encoded)
# the user opens the URL on the host, logs in, copies the base64 "IDE authorization token"
$U jbaLogin --token-file /path/token # setText into the field, "Check Token" once, "Get Started", focuses the frame
```

- URL and token belong to that dialog instance: don't restart the IDE between the two steps. A token works once; a
  rejected one prints `Token rejected: Wrong authorization token` — run `jbaLogin` again for a new URL.
- Don't type the token (`typeText`): a partial submit burns it. Don't press Esc on the dialog: it closes and a
  "Manage Subscriptions" license dialog takes its place; restart instead.
- Get secrets from the user via a secret prompt / file, not chat.

## Scripts (`runJs` / `callJs`)

- In scope: `robot` (assertj robot), `log`, and `global` — a `Map` shared by all scripts, the place to keep state
  between calls (a JS `var` is gone after the script). `component` only exists in fixture-scoped scripts.
- Prelude functions available in every script:
  - `loadPluginClass(pluginId, fqn)` — a `Class` from a plugin, searching its main and all content-module class loaders.
  - `pluginClass(pluginId, fqn)` — the same, wrapped for static calls:
    `pluginClass('IdeaVIM', 'com.maddyhome.idea.vim.api.VimInjectorKt').getInjector()`.
  - `normText(s)`, `focusOwner()`, `onEdt(fn)` (runs `fn` on the EDT via `invokeAndWait`, returns its result).
- Plain package access (`com.foo.Bar`) sees only the platform: a plugin class becomes `JavaPackage ... is not a
  function`. Use `pluginClass`. A Kotlin file with `@file:JvmName("X")` compiles to `X`, not `XKt` — check with
  `unzip -l <plugin>/lib/*.jar | grep Name`.
- Throw strings (`throw 'msg'`). `throw new Error(...)` or a Java exception gives an opaque `Internal Server Error`.
- Java strings aren't JS strings: `c.getClientProperty('x') === 'error'` is always false; wrap in `String(...)`.
- `new SomeInterface({ method: fn })` (Rhino JavaAdapter) only overrides **abstract** methods. For interfaces with
  default methods (`AnActionListener`, many listeners) the JS is never called; build a
  `java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), [iface], new java.lang.reflect.InvocationHandler({invoke: ...}))`
  instead (handle `hashCode`/`equals`/`toString`). For actions just use `traceActions`.
- The robot-server runs one request at a time and the CLI waits up to 120 s (`IUC_READ_TIMEOUT_SECONDS`). A script
  that outlives that keeps running and every later command queues behind it. Errors are one line; `IUC_DEBUG=1`
  prints the stack trace.

## Example flow — open the New Project dialog

```sh
IDE="/Users/wkeska/IdeaProjects/ideavim/out/ide-tests/cache/builds/IU-261.23567.71/IntelliJ IDEA.app"
~/.claude/skills/idea-ui-controller/idea-ui-controller runIde "$IDE"              # ~60s cold, ~10s warm
~/.claude/skills/idea-ui-controller/idea-ui-controller welcomeVerify               # → "Welcome frame present."
~/.claude/skills/idea-ui-controller/idea-ui-controller welcomeNewProject           # clicks the New Project button
~/.claude/skills/idea-ui-controller/idea-ui-controller dialogVerify --title "New Project"
~/.claude/skills/idea-ui-controller/idea-ui-controller stopIde
```

## Example flow — read editor text after an action

```sh
# (IDE already running with a project open)
~/.claude/skills/idea-ui-controller/idea-ui-controller editorOpen --file "Main.kt"
before=$(~/.claude/skills/idea-ui-controller/idea-ui-controller editorGetText --file "Main.kt")
# ... trigger an action via actionMenuItem, a keystroke via runJs, etc. ...
after=$(~/.claude/skills/idea-ui-controller/idea-ui-controller editorGetText --file "Main.kt")
diff <(echo "$before") <(echo "$after")
```

## Example flow — exploring an unknown UI

When the xpath for a control isn't known, pipe `getState` directly into `grep` — only the matching lines reach your context, not the full tree:

```sh
~/.claude/skills/idea-ui-controller/idea-ui-controller getState | grep -n "accessiblename=\"[^\"]*Settings" | head
# pick an xpath from the surrounding attrs in the matched lines:
~/.claude/skills/idea-ui-controller/idea-ui-controller clickButton --xpath "//div[@accessiblename='Settings' and @class='JButton']"
```

If you need to search several times over the same snapshot, capture first and grep from the file:

```sh
~/.claude/skills/idea-ui-controller/idea-ui-controller getState > /tmp/ui.xml
grep -n "accessiblename" /tmp/ui.xml | head
grep -n "class=\"JButton\"" /tmp/ui.xml | head
```

## Example flow — IdeaVim-specific introspection via callJs

```sh
~/.claude/skills/idea-ui-controller/idea-ui-controller editorIsBlockCursor --file "Main.kt"   # uses platform caret API, works without IdeaVim on classpath
~/.claude/skills/idea-ui-controller/idea-ui-controller editorCaretShape --file "Main.kt"      # BLOCK / BAR / UNDERSCORE / DEFAULT
~/.claude/skills/idea-ui-controller/idea-ui-controller callJs --script "com.maddyhome.idea.vim.VimPlugin.getState().toString()"
```

Any script valid as a `RemoteRobot.callJs` body works — full Java reflection is available via Rhino. See the intellij-ui-test-robot docs for the sandbox.

## Example flow — drive Vim commands by keystroke

```sh
# IDE already running with a project + scratch file focused
~/.claude/skills/idea-ui-controller/idea-ui-controller sendKey --key ESCAPE       # ensure normal mode
~/.claude/skills/idea-ui-controller/idea-ui-controller editorMoveToLine --line 1
~/.claude/skills/idea-ui-controller/idea-ui-controller typeText --text "dd"       # delete line 1
~/.claude/skills/idea-ui-controller/idea-ui-controller typeText --text "u"        # undo
~/.claude/skills/idea-ui-controller/idea-ui-controller typeText --text "i"        # enter insert mode
~/.claude/skills/idea-ui-controller/idea-ui-controller typeText --text "hello\n"  # type "hello" + Enter
~/.claude/skills/idea-ui-controller/idea-ui-controller sendKey --key ESCAPE       # back to normal
~/.claude/skills/idea-ui-controller/idea-ui-controller editorGetText              # verify result
```

`typeText` is for printable chars and the common escapes (`\n`, `\t`, `\r`). Use `sendKey` for anything else — `ESCAPE`, `F1`, `LEFT`, `BACK_SPACE`, etc.

## Tips for agent use

- **Run `getState` between every UI action — but choose the right form.** The live UI is not deterministic across versions or between runs — dialogs shift, focus moves, indexing kicks in. After every `clickButton` / `welcome*` / `actionMenuItem` / `dialogClickButton` / any state-changing call, re-observe the UI before deciding the next step. Chaining actions blind is the fastest way to wedge. Pick the cheapest form that answers your question:
  - **`getStateSummary` is the default post-action observation.** It prints the focus summary (current + other windows) followed by a compact flat list of every interactive element visible right now — buttons, text fields, checkboxes, menus, lists, trees, dialogs — each with its ready-to-use xpath. One call gives you everything you need to pick the next action, at a fraction of the context cost of the raw tree. Use it after every state-changing action as your standard "what do I have in front of me?" check. You can also pipe it through grep when you need to narrow further:
    ```sh
    ~/.claude/skills/idea-ui-controller/idea-ui-controller getStateSummary                      # default: full interactive summary
    ~/.claude/skills/idea-ui-controller/idea-ui-controller getStateSummary | grep "Trust\|Open"  # filter to specific elements
    ```
  - `getState --no-tree` — **focus-only check.** Returns current window + other visible windows, nothing else. Use only when all you need to answer is "which window has focus?" (e.g. confirming `focusWindow` worked). Do not use when you need to find or act on any element.
  - `getState` (full tree, raw XML) — **fallback for unknown layouts.** Use when `getStateSummary` isn't giving you enough context — e.g. you need to understand containment (is this button inside a toolbar or a dialog?), find a component class that isn't in the interactive-element allowlist, or diagnose why an xpath isn't matching. Pipe through grep rather than reading the whole output raw:
    ```sh
    ~/.claude/skills/idea-ui-controller/idea-ui-controller getState | grep -n "accessiblename\|class=\"JButton\"" | head -20
    ~/.claude/skills/idea-ui-controller/idea-ui-controller getState > /tmp/ui.xml && grep -n "BorderlessTextField" /tmp/ui.xml
    ```
- **`Current window: <none>` means focus loss — act on it immediately.** If `getState` reports `Current window: <none>`, the IDE window is unfocused (a native dialog, OS event, or prior action stole it). Any keyboard or click action you send next will silently go nowhere. **Stop, call `focusWindow` (optionally `--title <substring>` to target a specific window), then call `getState` again to confirm focus is restored before continuing.** Do not skip this step and do not assume the next action will work anyway.
- **Multiple matches.** `clickButton --text` can match several components (e.g. a `JButton` and its child `JBLabel` both carry the label text). The CLI clicks the first match and prints a warning. When the action doesn't fire, add `--xpath` with a `@class` predicate: `"//div[@accessiblename='New Project' and @class='JButton']"`.
- **Quoting paths with spaces.** Always quote the `.app` path — it contains a space (`IntelliJ IDEA.app`).
- **Idempotency / stale state recovery.** `runIde` refuses to start an instance name that already runs (pass `--restart`). If a command errors with a connection failure (`ConnectException`, socket timeout), the daemon may be dead while its state file still points at it. Recover with `stopIde` (same `--instance`; it tolerates a stale state) then `runIde` again. Dead instances' state files are cleaned up by the next `runIde`.
- **State files.** `~/.idea-ui-controller/instances/<instance>.properties`: `pid`, `port`, `sandbox`, `container`, `ide`.
- **Prefer clicks and keystrokes over scripts.** When you can achieve a goal through the IDE's own UI — menu items, buttons, keyboard shortcuts, dialogs — do that instead of reaching for `callJs`/`runJs`. UI-path actions exercise the real feature surface and are more likely to catch regressions. Reserve scripts for things the UI cannot expose: reading internal state, triggering non-interactive platform APIs, or recovering from a wedged state where no UI path is available.
- **`runJs` + EDT footgun.** Never call long-running or EDT-blocking operations directly (`ProjectUtil.openOrImport`, full project indexing triggers, modal dialogs spun on the EDT) from a `runJs` with `--edt` — they starve the robot-server and wedge the IDE. Wrap them in `ApplicationManager.getApplication().invokeLater(...)` or use a background task. `WriteAction.run(...)` inside `invokeAndWait(...)` is fine for short write-path edits (that's what `createScratchFile` / `editorInjectText` do).
- **Do NOT `sleep`.** The CLI is synchronous — every subcommand blocks until it finishes. External waits are pure waste.
- **`callJs` top-level context.** A bare script has `robot`, `log` and `global`, but no `component` — use `focusOwner()` or `java.awt.*` APIs, or a fixture-scoped command.
- **HTML entities.** `getState` prints `&larr;`, `&hellip;` where the component has `←`, `…`. `getStateSummary` shows them decoded; xpaths and `--text` values with entities are decoded before matching, so both forms work.

## Known footguns

- **`typeText` in autocomplete / file-chooser fields can lose text.** `typeText` fires keystrokes one at a time. In any field that triggers live autocomplete or file-system lookups on each keystroke (file chooser `BorderlessTextField`, path inputs), the synchronous I/O on every character can time out the robot-server socket mid-string. The visible effect: if the field had selected text when `typeText` started, the first keystroke replaces the selection, then typing continues until the socket times out — leaving only the tail of the intended string in the field. **Fix:** `typeText --set --text '/your/path/here'` (setText on the focused field, no keystrokes).

- **`sendKey` has no modifier support.** `sendKey --key A` types `a`, it does not select-all. For any Ctrl/Cmd/Shift/Alt combo, use `sendKeyCombo`. To save a file: `sendKeyCombo --key S --modifier META`. To select-all: `sendKeyCombo --key A --modifier META`.

- **`listSelect` crashes on JTree.** It calls `.getSize()` which is a `JList` method. The Project tool window tree is a `JTree` — use `treeSelect` instead.

## When NOT to use

- If the task is pure unit/integration testing — use the project's test harness.
- If the user wants to read static source — use normal file tools.
- If no IDE is required (e.g. running a Gradle task, grepping code) — just run that task directly.

## Reference

- intellij-ui-test-robot (underlying library): https://github.com/JetBrains/intellij-ui-test-robot
- Fixture catalog mirrors IdeaVim's `ui-fixtures/src/testFixtures/kotlin/ui/pages/` — `Editor.kt`, `IdeaFrame.kt`, `WelcomeFrame.kt`, `DialogFixture.kt`, `SearchEverywhere.kt`, `ActionMenuFixture.kt`, `ActionLinkFixture.java`, `Gutter.kt`.
- Source of this tool: `~/IdeaProjects/idea-ui-controller/`.
