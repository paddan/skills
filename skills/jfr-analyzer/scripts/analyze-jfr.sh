#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <recording.jfr> [output-dir]" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage
JFR_FILE=$1
[[ -f "$JFR_FILE" ]] || { echo "JFR file not found: $JFR_FILE" >&2; exit 1; }
command -v jfr >/dev/null 2>&1 || { echo "'jfr' not found. Use a JDK installation, not a JRE-only runtime." >&2; exit 1; }

if [[ $# -eq 2 ]]; then
  OUT=$2
else
  base=$(basename "$JFR_FILE" .jfr)
  OUT="./${base}-jfr-analysis"
fi
if [[ -e "$OUT" || -L "$OUT" ]]; then
  [[ -d "$OUT" && ! -L "$OUT" && -z "$(ls -A "$OUT")" ]] || {
    echo 'Output directory must be new or empty; existing results are preserved.' >&2
    exit 2
  }
fi
mkdir -p "$OUT/views" "$OUT/raw"
FAILURES=0
printf 'report\texit_code\tstderr\n' > "$OUT/report-status.tsv"

capture() {
  local report=$1 code=0
  shift
  if "$@" > "$OUT/$report.partial" 2> "$OUT/$report.stderr"; then
    mv "$OUT/$report.partial" "$OUT/$report"
  else
    code=$?
    FAILURES=$((FAILURES + 1))
  fi
  printf '%s\t%s\t%s\n' "$report" "$code" "$report.stderr" >> "$OUT/report-status.tsv"
}

JFR_VERSION=$(jfr --version 2>&1 | head -1 || true)
printf '%s\n' "$JFR_VERSION" > "$OUT/jfr-tool-version.txt"

# Core metadata. These work on older JDKs as well.
capture summary.txt jfr summary "$JFR_FILE"
capture metadata.txt jfr metadata "$JFR_FILE"

HAS_VIEW=false
if jfr help view >/dev/null 2>&1; then
  HAS_VIEW=true
fi
printf '%s\n' "$HAS_VIEW" > "$OUT/has-view.txt"

# Extract event type names/counts from summary in a simple searchable form.
# Keep the original summary as the source of truth because formatting differs by JDK.
awk '
  BEGIN { in_events=0 }
  /^ Event Type/ { in_events=1; next }
  in_events && /^[[:space:]]*$/ { next }
  in_events && $1 ~ /^[[:alnum:]_.\-$]+$/ && $2 ~ /^[0-9]+$/ { print $1 "\t" $2 }
' "$OUT/summary.txt" > "$OUT/event-counts.tsv" || true

# Best-effort list of custom/non-JDK event types.
awk -F '\t' '
  $1 !~ /^(jdk\.|java\.|javax\.|sun\.|com\.oracle\.)/ && NF >= 2 { print }
' "$OUT/event-counts.tsv" > "$OUT/custom-event-counts.tsv" || true

if $HAS_VIEW; then
  capture event-types.txt jfr view types "$JFR_FILE"

  VIEWS=(
    recording
    system-information
    jvm-flags
    active-settings
    cpu-information
    cpu-load
    cpu-load-samples
    thread-cpu-load
    hot-methods
    native-methods
    gc-configuration
    heap-configuration
    gc
    gc-cpu-time
    gc-pauses
    gc-pause-phases
    gc-concurrent-phases
    safepoints
    allocation-by-site
    allocation-by-class
    allocation-by-thread
    thread-allocation
    object-statistics
    memory-leaks-by-site
    memory-leaks-by-class
    contention-by-site
    contention-by-class
    contention-by-thread
    exception-count
    exception-by-site
    exception-by-message
    file-reads-by-path
    file-writes-by-path
    socket-reads-by-host
    socket-writes-by-host
    network-utilization
    thread-count
    pinned-threads
    deoptimizations-by-site
    deoptimizations-by-reason
    longest-compilations
    compiler-statistics
    compiler-phases
    vm-operations
    events-by-name
    events-by-count
  )

  for view in "${VIEWS[@]}"; do
    capture "views/${view}.txt" jfr view --width 200 --cell-height 12 "$view" "$JFR_FILE"
  done
else
  # JDK 17 fallback: extract focused raw events. Some event types may not be present.
  # jfr print accepts comma-separated event filters and simply emits matching events.
  # Keep this compatible with macOS Bash 3.2 (no associative arrays).
  while IFS='|' read -r group events; do
    [[ -n "$group" ]] || continue
    capture "raw/${group}.txt" jfr print --stack-depth 64 --events "$events" "$JFR_FILE"
  done <<'GROUPS'
cpu|jdk.CPULoad,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ThreadCPULoad
gc|jdk.GarbageCollection,jdk.GCPhasePause,jdk.GCHeapSummary,jdk.G1HeapSummary,jdk.PSHeapSummary,jdk.ParallelOldGarbageCollection
allocation|jdk.ObjectAllocationSample,jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.ThreadAllocationStatistics,jdk.OldObjectSample
contention|jdk.JavaMonitorEnter,jdk.JavaMonitorWait,jdk.ThreadPark
exceptions|jdk.JavaExceptionThrow,jdk.ExceptionStatistics
io|jdk.FileRead,jdk.FileWrite,jdk.SocketRead,jdk.SocketWrite
jit|jdk.Compilation,jdk.CompilerPhase,jdk.Deoptimization,jdk.CodeCacheStatistics
threads|jdk.ThreadStart,jdk.ThreadEnd,jdk.JavaThreadStatistics
GROUPS
fi

# Build a small navigation file for the agent/human.
{
  echo "# JFR analysis index"
  echo
  if [[ "$FAILURES" -gt 0 ]]; then
    echo "Status: INCOMPLETE — $FAILURES reports failed."
  else
    echo 'Status: COMPLETE — all requested commands succeeded; empty reports do not prove absence of behavior.'
  fi
  echo
  echo '- `report-status.tsv`: exit code and stderr path for every report command.'
  echo '- Failed stdout is retained as `.partial`; diagnostics are retained as `.stderr`.'
  echo '- Unsupported views are command failures, not evidence of missing events.'
  if [[ "$FAILURES" -gt 0 ]]; then
    echo
    echo '## Failed reports'
    awk -F '\t' 'NR > 1 && $2 != 0 { printf "- `%s`: exit %s; see `%s`\n", $1, $2, $3 }' "$OUT/report-status.tsv"
  fi
  echo
  echo "- Recording: \`$JFR_FILE\`"
  echo "- jfr tool: \`$JFR_VERSION\`"
  echo "- Aggregated views available: \`$HAS_VIEW\`"
  echo
  echo "## Core files"
  echo "- \`summary.txt\`: event counts and recording summary"
  echo "- \`metadata.txt\`: event schemas and recording metadata"
  echo "- \`event-counts.tsv\`: best-effort event count table"
  echo "- \`custom-event-counts.tsv\`: best-effort non-JDK event list"
  if $HAS_VIEW; then
    echo "- \`event-types.txt\`: event types present in the recording"
    echo
    echo "## Generated views"
    for f in "$OUT"/views/*.txt; do
      [[ -e "$f" ]] || continue
      basename "$f"
    done | sort | sed 's/^/- `views\//; s/$/`/'
  else
    echo
    echo "## JDK 17 fallback extracts"
    for f in "$OUT"/raw/*.txt; do
      [[ -e "$f" ]] || continue
      basename "$f"
    done | sort | sed 's/^/- `raw\//; s/$/`/'
  fi
} > "$OUT/INDEX.md"

echo "$OUT"
if [[ "$FAILURES" -gt 0 ]]; then exit 1; fi
