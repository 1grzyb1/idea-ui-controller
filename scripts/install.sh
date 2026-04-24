#!/usr/bin/env bash
#
# Installs idea-ui-controller:
#   1. Builds the self-executing fat jar via ./gradlew executable
#   2. Symlinks the binary into $BIN_DIR (default: ~/.local/bin)
#   3. Symlinks the skill into ~/.claude/skills/idea-ui-controller/SKILL.md
#   4. Copies omniparse.py helper into $LIB_DIR (default: ~/.local/lib/idea-ui-controller)
#
# Override install locations: BIN_DIR=/somewhere LIB_DIR=/elsewhere ./scripts/install.sh
# Re-running is idempotent — symlinks are replaced in place.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"
LIB_DIR="${LIB_DIR:-$HOME/.local/lib/idea-ui-controller}"
SKILL_DIR="$HOME/.claude/skills/idea-ui-controller"

echo "Repo:     $REPO_DIR"
echo "BIN_DIR:  $BIN_DIR"
echo "LIB_DIR:  $LIB_DIR"
echo "Skill:    $SKILL_DIR"
echo

echo "[1/4] Building executable (./gradlew executable)..."
cd "$REPO_DIR"
./gradlew --console=plain --quiet executable
BINARY="$REPO_DIR/build/libs/idea-ui-controller"
test -x "$BINARY" || { echo "error: expected $BINARY to exist and be executable"; exit 1; }

echo "[2/4] Symlinking binary to $BIN_DIR/idea-ui-controller"
mkdir -p "$BIN_DIR"
ln -sfn "$BINARY" "$BIN_DIR/idea-ui-controller"

echo "[3/4] Symlinking skill to $SKILL_DIR/SKILL.md"
mkdir -p "$SKILL_DIR"
SKILL_SRC="$REPO_DIR/.claude/skills/idea-ui-controller/SKILL.md"
if [ -e "$SKILL_DIR/SKILL.md" ] && [ ! -L "$SKILL_DIR/SKILL.md" ]; then
  BACKUP="$SKILL_DIR/SKILL.md.bak.$(date +%s)"
  echo "  found a regular file at $SKILL_DIR/SKILL.md; moving to $BACKUP"
  mv "$SKILL_DIR/SKILL.md" "$BACKUP"
fi
ln -sfn "$SKILL_SRC" "$SKILL_DIR/SKILL.md"

echo "[4/4] Installing omniparse.py + Python deps to $LIB_DIR"
mkdir -p "$LIB_DIR"
cp "$REPO_DIR/scripts/omniparse.py" "$LIB_DIR/omniparse.py"
chmod +x "$LIB_DIR/omniparse.py"
VENV="$LIB_DIR/venv"
if [ ! -f "$VENV/bin/python3" ]; then
  echo "      Creating venv at $VENV ..."
  python3 -m venv "$VENV"
fi
echo "      Installing Python dependencies ..."
"$VENV/bin/pip" install --quiet \
  torch torchvision \
  "transformers<5.0.0" \
  ultralytics \
  huggingface_hub \
  einops \
  timm \
  Pillow \
  numpy

echo
echo "Done."
echo

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
