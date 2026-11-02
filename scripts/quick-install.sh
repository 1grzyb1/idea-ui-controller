#!/usr/bin/env bash
#
# Quick install of idea-ui-controller together with its Claude skill, no Python / OmniParser.
#
#   gh api -H 'Accept: application/vnd.github.raw' repos/1grzyb1/idea-ui-controller/contents/scripts/quick-install.sh | bash
#
# or from a clone: ./scripts/quick-install.sh
#
#   1. Uses the clone it runs from, or clones/updates the repo in $IUC_SRC (default ~/.local/share/idea-ui-controller)
#   2. Builds the self-executing binary (Gradle downloads JDK 25 itself if it isn't installed)
#   3. Copies the binary and SKILL.md into ~/.claude/skills/idea-ui-controller/ - the path the skill calls the binary by
#   4. With --agents also copies the QA agents into ~/.claude/agents/
#
# Re-run to update. Needs git, a Java 17+ to run Gradle, and Docker Desktop for `runIde --docker`.
# parseScreenshot additionally needs ./scripts/install.sh (Python venv + models).

set -euo pipefail

REPO_SLUG="1grzyb1/idea-ui-controller"
SKILL_DIR="$HOME/.claude/skills/idea-ui-controller"
AGENTS=false
for arg in "$@"; do
  case "$arg" in
    --agents) AGENTS=true ;;
    *) echo "Unknown argument: $arg (supported: --agents)"; exit 1 ;;
  esac
done

SCRIPT_PATH="${BASH_SOURCE[0]:-}"
if [ -n "$SCRIPT_PATH" ] && [ -f "$SCRIPT_PATH" ] && [ -f "$(dirname "$SCRIPT_PATH")/../build.gradle.kts" ]; then
  REPO_DIR="$(cd "$(dirname "$SCRIPT_PATH")/.." && pwd)"
else
  # Piped into bash: no clone around us
  REPO_DIR="${IUC_SRC:-$HOME/.local/share/idea-ui-controller}"
  if [ -d "$REPO_DIR/.git" ]; then
    echo "[0/3] Updating $REPO_DIR ..."
    git -C "$REPO_DIR" pull --ff-only --quiet
  else
    echo "[0/3] Cloning $REPO_SLUG into $REPO_DIR ..."
    mkdir -p "$(dirname "$REPO_DIR")"
    # The repo is private: gh has the credentials, plain git needs an SSH key with access
    if command -v gh >/dev/null 2>&1; then
      gh repo clone "$REPO_SLUG" "$REPO_DIR" -- --quiet
    else
      git clone --quiet "git@github.com:$REPO_SLUG.git" "$REPO_DIR"
    fi
  fi
fi

command -v java >/dev/null 2>&1 || { echo "error: no java on PATH; Gradle needs Java 17+ to start (it fetches JDK 25 itself)"; exit 1; }

echo "[1/3] Building the binary in $REPO_DIR ..."
(cd "$REPO_DIR" && ./gradlew --console=plain --quiet executable)
BINARY="$REPO_DIR/build/libs/idea-ui-controller"
test -x "$BINARY" || { echo "error: expected $BINARY to exist and be executable"; exit 1; }

echo "[2/3] Installing the binary and the skill into $SKILL_DIR ..."
mkdir -p "$SKILL_DIR"
# Replace a symlink left by scripts/install.sh instead of writing through it into the repo
rm -f "$SKILL_DIR/SKILL.md" "$SKILL_DIR/idea-ui-controller"
cp "$REPO_DIR/.claude/skills/idea-ui-controller/SKILL.md" "$SKILL_DIR/SKILL.md"
cp "$BINARY" "$SKILL_DIR/idea-ui-controller"
chmod +x "$SKILL_DIR/idea-ui-controller"

if $AGENTS; then
  echo "[3/3] Installing the QA agents into ~/.claude/agents ..."
  mkdir -p "$HOME/.claude/agents"
  cp "$REPO_DIR"/.claude/agents/*.md "$HOME/.claude/agents/"
else
  echo "[3/3] Skipping the QA agents (pass --agents to install them)"
fi

echo
"$SKILL_DIR/idea-ui-controller" --version >/dev/null && echo "Done: $SKILL_DIR/idea-ui-controller"
echo "Next: start Docker Desktop, then ask Claude to use the idea-ui-controller skill, e.g."
echo "  $SKILL_DIR/idea-ui-controller runIde --docker IU-eap"
