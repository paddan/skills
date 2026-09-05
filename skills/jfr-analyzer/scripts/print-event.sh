#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <recording.jfr> <event-name-or-glob> [max-lines]" >&2
  exit 2
fi

JFR_FILE=$1
EVENT_FILTER=$2
MAX_LINES=${3:-1200}

[[ -f "$JFR_FILE" ]] || { echo "JFR file not found: $JFR_FILE" >&2; exit 1; }
command -v jfr >/dev/null 2>&1 || { echo "'jfr' not found" >&2; exit 1; }
[[ "$MAX_LINES" =~ ^[0-9]+$ ]] || { echo "max-lines must be an integer" >&2; exit 2; }

# Limit what enters the agent context. The source recording remains untouched.
jfr print --stack-depth 64 --events "$EVENT_FILTER" "$JFR_FILE" | sed -n "1,${MAX_LINES}p"
