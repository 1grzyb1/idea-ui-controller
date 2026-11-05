#!/bin/sh
# Starts the virtual display, then the command (the IDE startup script) on it.
set -e
SCREEN="${XVFB_SCREEN:-1920x1080x24}"
Xvfb :99 -screen 0 "$SCREEN" -nolisten tcp -ac &
export DISPLAY=:99
i=0
while [ ! -e /tmp/.X11-unix/X99 ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
# Live view in a browser: VNC on the display, noVNC serving it over a websocket on VIEW_PORT
if [ -n "$VIEW_PORT" ]; then
    x11vnc -display :99 -forever -shared -nopw -localhost -rfbport 5900 -quiet ${VIEW_ONLY:+-viewonly} >/tmp/x11vnc.log 2>&1 &
    websockify --web /usr/share/novnc "0.0.0.0:$VIEW_PORT" localhost:5900 >/tmp/websockify.log 2>&1 &
fi
exec "$@"
