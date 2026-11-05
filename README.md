# idea-ui-controller

Lets an AI agent (or a script) use a real JetBrains IDE the way a person does: start it, open a project, type, press
shortcuts, click buttons, read what's in the editor and on the screen. It's meant for checking IDE plugins end to end,
in the running IDE, without a human at the keyboard.

You don't run the commands yourself. You ask Claude Code, for example:

- *"QA the new 'Extract variable' popup in IntelliJ"*: an agent plans test scenarios, runs each one in the IDE and
  writes a pass/fail report with logs and a video of every scenario.
- *"Start Rider with my plugin build, reproduce this bug and check that my fix works"*: an agent starts the IDE with
  your plugin, reproduces the bug and shows you a recording.

## What it can do

- **Run the IDE in the background.** By default the IDE runs inside Docker on a virtual screen, so it never touches
  your keyboard, mouse or windows, and you can keep working. Running it directly on your Mac works too, e.g. for
  macOS-specific bugs.
- **Fresh IDE every time.** Each start gets a clean sandbox with only the plugins you asked for. Your own IDE settings
  are never touched.
- **Any JetBrains IDE and version.** IntelliJ IDEA, PyCharm, Rider, ... from your installed app, or a build number
  (`IU-263.7307.44`, or `IU-eap` for the latest EAP), downloaded once and cached.
- **Remote Development (split mode).** Runs an IDE backend together with the JetBrains Client, for bugs that only
  show up there (e.g. Rider).
- **Several IDEs at once.** Agents working in parallel each get their own IDE and can't interfere with each other.
- **Watch it live.** Every Docker IDE has a live view you open in a browser (e.g. the browser tab in T3 Code).
- **Recordings.** An agent can record what it did as an MP4 and show it to you.

## The QA agents

The repo ships two Claude Code agents:

- **`qa-ide-feature`**: give it a feature description. It reads the relevant code, designs 5–10 test scenarios,
  runs them one by one, learns from each run (what worked, what to avoid) and writes `report.md` into
  `qa-runs/<feature>-<time>/` with a log and a recording per scenario.
- **`qa-scenario-runner`**: runs one scenario for it. Not meant to be used directly.

## Installation

Needs git, Java 17+ (to start the build; Java 25 is downloaded automatically) and Docker Desktop.

```sh
gh api -H 'Accept: application/vnd.github.raw' repos/1grzyb1/idea-ui-controller/contents/scripts/quick-install.sh | bash
# with the QA agents too:
gh api -H 'Accept: application/vnd.github.raw' repos/1grzyb1/idea-ui-controller/contents/scripts/quick-install.sh | bash -s -- --agents
```

The repo is private, so `gh` has to be logged in with access to it. This installs the tool and its Claude skill into
`~/.claude/skills/idea-ui-controller/`. Run it again to update.

`./scripts/install.sh` also installs a local screenshot parser (`parseScreenshot`, needs Python) for finding things
on screen that the IDE doesn't describe, like icon-only buttons.

## Disk space

The Linux IDE builds used in Docker take 3–7 GB each. Only the 3 most recently used are kept
(`IDEA_UI_CONTROLLER_IDE_CACHE=N` to change). `idea-ui-controller ideCache` shows what's cached,
`ideCache --keep N` cleans up.

## For developers

How the agents use the tool, with every command and its pitfalls, is in
[`.claude/skills/idea-ui-controller/SKILL.md`](.claude/skills/idea-ui-controller/SKILL.md). How the code is laid out,
and the rule that every CLI change updates that file, is in [`CLAUDE.md`](CLAUDE.md). It's built on JetBrains'
[intellij-ui-test-robot](https://github.com/JetBrains/intellij-ui-test-robot).

```sh
./gradlew install   # build and install the binary where the skill expects it
```
