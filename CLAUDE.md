# idea-ui-controller

This project has three tightly coupled pieces. A change to the CLI API (new subcommand, renamed flag, changed behavior) **must** be reflected in the SKILL immediately — the agents derive everything they know about the CLI from the SKILL.

```
 ┌─────────────────────────────────┐
 │  CLI executable                 │  drives the IDE over robot-server RPC
 │  src/main/kotlin/…/Main.kt      │
 │  src/main/kotlin/…/Pages.kt     │
 └────────────┬────────────────────┘
              │  documented by
              ▼
 ┌─────────────────────────────────┐
 │  SKILL                          │  teaches Claude how to use the CLI
 │  .claude/skills/                │
 │    idea-ui-controller/SKILL.md  │
 └────────────┬────────────────────┘
              │  consumed by
              ▼
 ┌─────────────────────────────────┐
 │  AGENTS                         │  automate IDE QA via the CLI
 │  .claude/agents/                │
 │    qa-ide-feature.md            │  orchestrator — plans + reports
 │    qa-scenario-runner.md        │  executor — drives the IDE step by step
 └─────────────────────────────────┘
```

## 1. The CLI executable

**Source:** `src/main/kotlin/com/wkeska/ideauicontroller/`
- `Main.kt` — daemon lifecycle (`runIde`, `stopIde`, `getState`, `probe`, `focusWindow`), generic actions (`clickButton`, `runJs`, `callJs`), and keyboard primitives (`sendKey`, `typeText`).
- `DetachedIdeLauncher.kt` / `DockerIdeLauncher.kt` — start the IDE on the host, or its Linux build in Docker on Xvfb (`runIde --docker`; image sources in `src/main/resources/docker/`), also in Remote Development split mode (`runIde --docker --split-mode`).
- `Pages.kt` — page-scoped commands: Welcome frame, Editor, IDE frame, Dialog, Search Everywhere, Action Menu, Gutter, generic widgets (`listSelect`, `textFieldSet`, `checkboxSet`, `dialogClickButton`), and high-level flows (`projectCreateFromDialog`, `createScratchFile`).
- `Extras.kt` — the `runJs`/`callJs` script prelude (`loadPluginClass`, `pluginClass`, `normText`, `focusOwner`, `onEdt`), HTML-entity decoding for xpaths and texts, `linkClick`, `clipboard`, `focusedComponent`, `traceActions`, `jbaLogin`.

**Underlying library:** JetBrains [`intellij-ui-test-robot`](https://github.com/JetBrains/intellij-ui-test-robot) (NOT Robot Framework). All locator syntax (`byXpath`), fixture semantics, and `callJs`/`runJs` contracts come from there.

**Build:**
```sh
./gradlew install             # rebuild + copy binary to ~/.claude/skills/idea-ui-controller/idea-ui-controller (the path SKILL.md documents)
./gradlew executable          # only produce build/libs/idea-ui-controller (self-executing fat jar)
./gradlew shadowJar           # only produce build/libs/idea-ui-controller-fat.jar
```

After any CLI change, run `./gradlew install` — agents reading SKILL.md invoke the binary at its skill-folder path, so nothing takes effect until it's been copied there. `executable` declares the shadow jar as an input, so a stale stub is never silently left behind when sources change.

**Runtime requirement:** Java 25. The fat-jar stub passes the required `--add-opens` flags automatically and hardcodes `JAVA_HOME` at build time — no env vars required to run the CLI.

**State:** `~/.idea-ui-controller/state.properties` — holds `pid`, `port`, `sandbox` of the running daemon. One IDE at a time.

**Adding a new subcommand checklist:**
1. Write the `@Command` class in `Main.kt`, `Pages.kt` or `Extras.kt`.
2. Register it in the `subcommands` array on `App` in `Main.kt`.
3. Update `SKILL.md` — add the command to the **Command catalog** section and, if behavior is non-obvious, add a usage note or example. The agents read nothing else.

## 2. The SKILL

**File:** `.claude/skills/idea-ui-controller/SKILL.md`

This is the single source of truth Claude uses when operating the CLI. It covers:
- Prerequisites (Java 25 bundled, binary at `~/.claude/skills/idea-ui-controller/idea-ui-controller`, IDE path format)
- Startup time expectations
- Sandbox lifecycle
- Full command catalog with flags and descriptions
- Example flows
- Agent-use tips and footguns

**Rule:** Every time the CLI API changes, update SKILL.md in the same commit. The agents have no other way to learn about new commands or changed behavior.

## 3. The Agents

**Files:** `.claude/agents/qa-ide-feature.md` and `.claude/agents/qa-scenario-runner.md`

Two subagents that together implement automated IDE QA. They are invoked via the `qa-ide-feature` skill (user-facing) or directly as subagent types.

**`qa-ide-feature`** (model: opus) — orchestrator:
- Takes a feature description, gathers context from source + live IDE, designs 5–10 test scenarios, dispatches them one by one to `qa-scenario-runner`, accumulates lessons between runs, and writes a final `report.md`.
- Stores run artifacts under `<project>/qa-runs/<slug>-<timestamp>/`.
- Never drives the IDE itself — delegates all CLI calls to the runner.

**`qa-scenario-runner`** (model: sonnet) — executor:
- Receives exactly one scenario + lessons + prior log paths from the orchestrator.
- Drives the IDE step by step via `idea-ui-controller` Bash calls.
- Writes a detailed step-by-step log to the path the orchestrator specified.
- Returns a short summary (status + one-liner) to the orchestrator.
- Reads SKILL.md at `.claude/skills/idea-ui-controller/SKILL.md` for the CLI reference.

**The agents are not end-user tools.** `qa-ide-feature` is the user entry point; `qa-scenario-runner` is internal to it.