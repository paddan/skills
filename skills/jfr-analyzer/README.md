# jfr-analyzer

Portable Agent Skill for analyzing Java Flight Recorder (`.jfr`) files with the JDK `jfr` CLI.

## Requirements

- JDK 17 or newer with the `jfr` command on `PATH`
- JDK 21+ recommended because `jfr view` provides built-in aggregation views
- Bash for the bundled helper scripts

## Install

```bash
./install.sh --agents --skill jfr-analyzer
```

Run this command from the repository root. The shared installer also supports
`--codex`, `--claude`, `--opencode`, `--pi`, and `--all`. Omit `--skill jfr-analyzer` to
install every skill. Add `--copy` for physical copies instead of symlinks.
See the [repository installation instructions](../../README.md#installation)
for target directories and backup behavior.

## Usage

The broad analyzer requires a new or empty output directory to avoid mixing or
overwriting results from different recordings. `INDEX.md` marks the result
`COMPLETE` or `INCOMPLETE`; `report-status.tsv` records each command's exit code
and diagnostic file. Failed commands preserve `.stderr` and `.partial` output
and make the analyzer exit nonzero. Unsupported views are reported as failures,
while successful reports with no matching events remain successful.

Package reports are written to a temporary file and replace the requested report
only after successful analysis. The recording itself, hard-link aliases, and
symlink output paths are rejected as report destinations.

Ask the agent to analyze a JFR file, or invoke the skill explicitly where supported.

Examples:

```text
Analyze /tmp/service.jfr and tell me why latency increased.
```

```text
Analyze service.jfr. Focus on GC and allocation pressure.
```

```text
Compare before.jfr and after.jfr after the cache change.
```

To focus on your own code, give the agent a package prefix:

```text
Analyze service.jfr. Focus on package se.polisen.luna and tell me how classes in that package behave.
```

The package analyzer includes subpackages and attributes CPU samples, allocations, blocking, exceptions and I/O by stack frame. It also produces a per-class activity overview. Calls into JDK/framework/library code are still visible when your package is lower in the stack, which is useful for spotting expensive work initiated by application code.

You can run the package analyzer directly:

```bash
./scripts/analyze-package.sh service.jfr se.polisen.luna package-report.md
```

The skill first runs `scripts/analyze-jfr.sh`, which creates compact reports from the recording. It only prints raw events for targeted drill-down, avoiding the rather heroic mistake of dumping an entire JFR file into model context.

## Package-focused analysis

`analyze-package.sh` uses the JDK `jdk.jfr.consumer` API through a dependency-free Java source file. It therefore works independently of `jfr view` and is supported on JDK 17+. The report contains:

- package share of CPU samples
- direct package CPU samples versus samples executing in called JDK/library code
- hot package methods and threads
- a per-class activity table
- allocation sites and allocated classes
- blocking/contention sites and duration
- exception classes and throw sites
- file/socket I/O sites, targets, duration and bytes
- JIT compilation and deoptimization attributable to package methods

Package attribution is evidence that the package participated in the call stack. It is not proof that every downstream operation is a defect in that package. Machines remain annoyingly resistant to simplistic blame assignment.
