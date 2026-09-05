#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: analyze-package.sh <recording.jfr> <package-prefix> [output-file] [top-n]

Examples:
  analyze-package.sh service.jfr se.polisen.luna
  analyze-package.sh service.jfr se.polisen.luna report.md 30
USAGE
  exit 2
}

[[ $# -ge 2 && $# -le 4 ]] || usage
JFR_FILE=$1
PACKAGE=$2
OUTPUT=${3:-}
TOP=${4:-20}

[[ -f "$JFR_FILE" ]] || { echo "JFR file not found: $JFR_FILE" >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "'java' not found. Use a JDK 17+ installation." >&2; exit 1; }

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

run() {
  java --add-modules jdk.jfr "$SCRIPT_DIR/PackageJfrAnalyzer.java" "$JFR_FILE" "$PACKAGE" "$TOP"
}

if [[ -n "$OUTPUT" ]]; then
  [[ ! "$JFR_FILE" -ef "$OUTPUT" ]] || { echo 'Output must not be the recording (including links).' >&2; exit 2; }
  [[ ! -d "$OUTPUT" && ! -L "$OUTPUT" ]] || { echo 'Output must be a regular file path, not a directory or symlink.' >&2; exit 2; }
  mkdir -p "$(dirname -- "$OUTPUT")"
  report_temp=$(mktemp "$(dirname -- "$OUTPUT")/.package-report.XXXXXX")
  trap 'rm -f "$report_temp"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  run > "$report_temp"
  mv -f "$report_temp" "$OUTPUT"
  echo "$OUTPUT"
else
  run
fi
