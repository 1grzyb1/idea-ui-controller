#!/usr/bin/env bash
#
# Installs idea-ui-controller:
#   1. Builds the self-executing fat jar via ./gradlew executable
#   2. Symlinks the binary into $BIN_DIR (default: ~/.local/bin)
#   3. Symlinks the skill into ~/.claude/skills/idea-ui-controller/SKILL.md
#
# Override install location: BIN_DIR=/somewhere ./scripts/install.sh
# Re-running is idempotent — symlinks are replaced in place.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"
SKILL_DIR="$HOME/.claude/skills/idea-ui-controller"

echo "Repo:     $REPO_DIR"
echo "BIN_DIR:  $BIN_DIR"
echo "Skill:    $SKILL_DIR"
echo

echo "[1/3] Building executable (./gradlew executable)..."
cd "$REPO_DIR"
./gradlew --console=plain --quiet executable
BINARY="$REPO_DIR/build/libs/idea-ui-controller"
test -x "$BINARY" || { echo "error: expected $BINARY to exist and be executable"; exit 1; }

echo "[2/3] Symlinking binary to $BIN_DIR/idea-ui-controller"
mkdir -p "$BIN_DIR"
ln -sfn "$BINARY" "$BIN_DIR/idea-ui-controller"

echo "[3/3] Symlinking skill to $SKILL_DIR/SKILL.md"
mkdir -p "$SKILL_DIR"
SKILL_SRC="$REPO_DIR/.claude/skills/idea-ui-controller/SKILL.md"
if [ -e "$SKILL_DIR/SKILL.md" ] && [ ! -L "$SKILL_DIR/SKILL.md" ]; then
  BACKUP="$SKILL_DIR/SKILL.md.bak.$(date +%s)"
  echo "  found a regular file at $SKILL_DIR/SKILL.md; moving to $BACKUP"
  mv "$SKILL_DIR/SKILL.md" "$BACKUP"
fi
ln -sfn "$SKILL_SRC" "$SKILL_DIR/SKILL.md"

echo
echo "Done."
echo

# Runtime sanity: the binary needs Java 25 on PATH to run (build can use any JDK 17+).
if java -version 2>&1 | head -1 | grep -qE '"25\.'; then
  echo "Java on PATH: $(java -version 2>&1 | head -1)"
else
  cat <<EOF
Warning: Java 25 is not the active runtime on PATH. The binary requires
Java 25 to run. Current: $(java -version 2>&1 | head -1 || echo "java not found")

To use the tool, set a JDK 25 as active, e.g.:
    export JAVA_HOME=\$(/usr/libexec/java_home -v 25 2>/dev/null || echo /path/to/jdk-25)
    export PATH="\$JAVA_HOME/bin:\$PATH"
EOF
fi

# PATH hint
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *)
    echo
    echo "Note: $BIN_DIR is not on your PATH. Add to your shell profile:"
    echo "    export PATH=\"$BIN_DIR:\$PATH\""
    ;;
esac

echo
echo "Verify with: idea-ui-controller --help"
