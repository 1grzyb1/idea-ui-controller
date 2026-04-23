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
  daemon's `pid`, `port`, and sandbox directory. It is the single source of
  truth for "is there an IDE running?".
- **Sandbox.** Every `runIde` creates a fresh temp sandbox directory, installs
  the robot-server plugin into it, and tears it down on `stopIde`.
- **Exit codes.** `0` = success, non-zero = failure. Every command prints a
  human-readable status line on success.

## Runtime requirements

- **Java 25.** The fat-jar stub passes the required `--add-opens` flags
  automatically. Older JDKs are unsupported.
- **IDE path.** On macOS this is the `.app` bundle directory (e.g.
  `IntelliJ IDEA.app`). On Linux/Windows it is the unpacked distribution
  directory.

## Command surface

### Daemon lifecycle

- **`runIde <idePath> [--port N] [--timeout-seconds N]`**
  Launch the IDE with the robot-server plugin, wait until the robot-server
  responds, and register it as the active daemon. Default port `8082`,
  default timeout `180s`. Refuses to start if an IDE is already registered
  and alive — call `stopIde` first.

- **`stopIde`**
  Kill the registered IDE process and delete its sandbox. Tolerates stale
  state (registered PID already dead) and always clears the state file.

- **`probe [--port N]`**
  One `callJs("true")` round-trip against the daemon. Prints `OK: true` on
  success, the exception class+message on failure. Cheapest way to ask
  "is the daemon healthy?".

### Observation

- **`getState`**
  Fetch the full UI component tree (XML) from the robot-server and print it
  to stdout. Used to discover xpaths for controls whose locators aren't
  known in advance.

### Action

- **`clickButton (--xpath <xp> | --text <t>) [--timeout-seconds N]`**
  Find a component and click it. `--xpath` is a raw locator (preferred
  once known); `--text` resolves to
  `//div[@text='t' or @accessiblename='t' or @visible_text='t']` as a
  convenience. Default wait `10s`. When multiple components match, the CLI
  clicks the first and prints a warning — add a `@class` predicate to
  disambiguate.

## Build

- `./gradlew shadowJar` — fat jar at `build/libs/idea-ui-controller-fat.jar`.
- `./gradlew executable` — self-executing stub at
  `build/libs/idea-ui-controller` (shell header + fat jar concatenated, set
  executable).

## Non-goals

- No parallel IDE instances. One daemon at a time, tracked via one state file.
- No test-framework integration. This tool drives an IDE; it does not assert.
  Callers compose behavior from the primitives above.
- No Robot Framework compatibility. The name collides, but the protocol,
  syntax, and semantics are entirely JetBrains' `intellij-ui-test-robot`.
