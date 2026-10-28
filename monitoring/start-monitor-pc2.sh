#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MONITOR="$SCRIPT_DIR/../scripts/multipaper-beta-monitor.py"

if [ "$#" -lt 1 ]; then
    printf '%s\n' "Usage: $0 <private-config.json> [monitor arguments...]" >&2
    exit 2
fi

CONFIG=$1
shift

if [ ! -f "$MONITOR" ]; then
    printf '%s\n' "Monitor script not found: $MONITOR" >&2
    exit 1
fi
if [ ! -f "$CONFIG" ]; then
    printf '%s\n' "Private monitor config not found: $CONFIG" >&2
    exit 1
fi

PYTHON=$(command -v python3 || true)
if [ -z "$PYTHON" ]; then
    printf '%s\n' "Python 3.10 or newer is required (python3 was not found)." >&2
    exit 1
fi
if ! "$PYTHON" -c 'import sys; raise SystemExit(sys.version_info < (3, 10))'; then
    printf '%s\n' "Python 3.10 or newer is required." >&2
    exit 1
fi

exec "$PYTHON" "$MONITOR" --config "$CONFIG" "$@"
