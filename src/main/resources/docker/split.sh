#!/bin/sh
# Remote Development split mode in one container: the IDE as a backend (`serverMode`), then the JetBrains Client
# bundled with it, connected to that backend. The robot-server runs in the client.
# Usage: split.sh <backend script> <project> <backend log> <client script>
# The backend's environment variables are passed with a BACKEND_ prefix, so the client doesn't pick them up.
BACKEND="$1"; PROJECT="$2"; BACKEND_LOG="$3"; CLIENT="$4"

env | sed -n 's/^BACKEND_\([A-Za-z0-9_]*=.*\)/\1/p' > /tmp/backend.env
(set -a; . /tmp/backend.env; set +a; exec "$BACKEND" serverMode "$PROJECT") > "$BACKEND_LOG" 2>&1 &

echo "Waiting for the backend's join link in $BACKEND_LOG ..."
LINK=""
i=0
while [ -z "$LINK" ] && [ $i -lt 600 ]; do
  sleep 1
  i=$((i + 1))
  LINK=$(grep -o 'tcp://[^ ]*#[^ ]*' "$BACKEND_LOG" | head -1)
done
if [ -z "$LINK" ]; then
  echo "No join link from the backend after ${i}s" >&2
  exit 1
fi
echo "Join link: $LINK"
exec "$CLIENT" thinClient "$LINK"
