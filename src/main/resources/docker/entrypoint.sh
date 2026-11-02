#!/bin/sh
# Starts the virtual display, then the command (the IDE startup script) on it.
set -e
SCREEN="${XVFB_SCREEN:-1920x1080x24}"
Xvfb :99 -screen 0 "$SCREEN" -nolisten tcp -ac &
export DISPLAY=:99
i=0
while [ ! -e /tmp/.X11-unix/X99 ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
exec "$@"
