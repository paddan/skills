---
name: jfr-analyzer
description: Analyze Java Flight Recorder (.jfr) files for JVM and application performance problems including CPU hot spots, GC pauses, allocation pressure, lock contention, exceptions, I/O, safepoints, JIT/deoptimization, virtual-thread pinning, custom JFR events, and package-focused behavior. Use when the user provides or points to a .jfr file or asks for JFR performance analysis. When the user supplies a Java package prefix, focus attribution on classes and stack frames in that package and its subpackages. Do not diagnose memory leaks from allocation data alone.
license: MIT
compatibility: Requires a JDK with the jfr CLI. JDK 21+ recommended; JDK 17 supported with reduced aggregation.
metadata:
  audience: java-developers
  domain: jvm-performance
---

# JFR Analyzer

Analyze Java Flight Recorder files using the JDK's `jfr` CLI. Prefer deterministic extraction with the bundled scripts over dumping the entire recording into context.

## Inputs

The primary input is one `.jfr` file. If several recordings are supplied, analyze each separately first, then compare them.

An optional package prefix such as `se.polisen.luna` can be supplied. Treat the prefix as inclusive of subpackages. Package-focused analysis should answer how application classes participate in CPU use, allocation, blocking, exceptions, I/O, JIT/deoptimization, and relevant custom/other events.

Treat the recording as potentially sensitive. JFR data can contain class names, thread names, environment variables, system properties, file paths, host names, network endpoints, and custom application payloads. Do not upload or transmit it unless the user explicitly requests that.

## Workflow

1. Locate the `.jfr` file and confirm it exists.
2. Confirm `jfr` is available by running `jfr --version`.
3. Run:

   ```bash
   bash <skill-dir>/scripts/analyze-jfr.sh <recording.jfr> <output-dir>
   ```

   Resolve `<skill-dir>` to the directory containing this `SKILL.md`. Choose an output directory outside the skill directory, normally a temporary directory or a project-local ignored directory.

4. If the user supplied a Java package prefix, also run:

   ```bash
   bash <skill-dir>/scripts/analyze-package.sh <recording.jfr> <package-prefix> <output-dir>/package-report.md
   ```

   Read `package-report.md` before broad JVM reports. Use the broad reports to provide JVM context and to validate whether a package-local signal is actually important globally.
5. Read `<output-dir>/INDEX.md` first, including its completion status. The output directory must be new or empty. A nonzero analyzer exit can leave useful but incomplete results: inspect `report-status.tsv` and the referenced `.stderr` files, disclose failed reports, and distinguish unsupported views or extraction errors from successful reports with no events. Failed stdout is retained as `.partial`, not a completed report. Then inspect only the reports relevant to the user's problem. Do not ingest every report blindly.
6. Start with these areas unless the user's question is narrower:
   - `summary.txt` and `views/recording.txt`
   - CPU: `views/cpu-load.txt`, `views/hot-methods.txt`, `views/thread-cpu-load.txt`
   - GC: `views/gc.txt`, `views/gc-pauses.txt`, `views/gc-pause-phases.txt`, `views/safepoints.txt`
   - allocations: `views/allocation-by-site.txt`, `views/allocation-by-class.txt`, `views/thread-allocation.txt`
   - contention: `views/contention-by-site.txt`, `views/contention-by-class.txt`
   - exceptions: `views/exception-count.txt`, `views/exception-by-site.txt`
   - I/O: file and socket read/write reports
   - JIT: deoptimization and compilation reports
   - virtual threads: `views/pinned-threads.txt` when present
7. If a suspicious standard or custom event needs raw detail, use:

   ```bash
   bash <skill-dir>/scripts/print-event.sh <recording.jfr> '<event-name-or-glob>' [max-lines]
   ```

   Use a narrow event filter. Avoid printing all events unless the recording is tiny.
8. Consult `references/interpretation.md` before making causal claims or recommending JVM tuning.
9. Produce a prioritized report. Separate observed evidence from inference.

## Analysis rules

### Package-focused analysis

When a package prefix is supplied, use `scripts/analyze-package.sh`. It reads the recording through the standard `jdk.jfr.consumer` API and attributes events using stack frames instead of grepping formatted output. This works on JDK 17+ and avoids dependence on `jfr view` formatting.

Interpret package attribution carefully:

- A **direct/top-frame CPU sample** means the sampled instruction was in the package.
- A **package-attributed CPU sample** means the package appeared anywhere in the recorded stack. If the top frame is in the JDK, a library, JDBC driver, HTTP client, etc., the package may be the caller that led to that work.
- For allocation, blocking/waiting, exception, and I/O events, attribute the operation to the nearest matching package frame in the stack. This identifies the application call site, not automatically the root cause.
- The per-class activity overview is an activity map, not a severity ranking. High activity can be expected for central service, mapper, serialization, or repository classes.
- Package matching includes subpackages. `se.polisen.luna` matches `se.polisen.luna.foo.Bar` but not `se.polisen.lunar.Baz`.
- If relevant events lack stack traces, state that package attribution is incomplete. Do not infer ownership from unrelated class names.

Package-focused reports should normally discuss:

1. classes with the largest CPU sample presence,
2. hot package methods and external/JDK leaf methods executing above them,
3. classes and sites creating allocation pressure,
4. classes involved in monitor contention, parking, sleeping, or long waits,
5. exception-heavy classes and throw sites,
6. file/socket I/O initiated through package code,
7. package method compilation/deoptimization when recorded.


### CPU

Treat `hot-methods` as sampled evidence, not exact per-method CPU accounting. A method with a large fraction of execution samples is a candidate hot spot, especially when JVM/process CPU load is also high. Check stacks and calling context before recommending optimization.

Distinguish Java execution samples from native method samples when possible. High CPU can also come from GC, JIT compilation, native libraries, or kernel activity.

### GC and heap

Report collector, heap configuration, GC frequency, total pause time, longest pauses, and pause phases when recorded. Correlate GC CPU and application CPU where possible.

Do not call high allocation a memory leak. Allocation pressure can cause frequent GC while live-set size remains stable. A leak claim requires evidence such as retained/old-object samples, increasing live set across GCs, or an additional heap analysis.

If `memory-leaks-by-site` or `memory-leaks-by-class` is absent or empty, explicitly state that the recording may not contain OldObjectSample data and therefore cannot establish a leak.

### Contention and latency

Prioritize long or frequent monitor contention. `ThreadPark` can represent legitimate waiting and is not automatically lock contention. Correlate blocking sites with thread CPU and application behavior.

### Exceptions

High exception volume can create CPU and allocation overhead, but some frameworks use exceptions routinely. Identify the exception type and throwing site before labeling it a defect.

### I/O

Look for long-duration file/socket operations and concentration on specific paths or hosts. A slow read is evidence of waiting observed by the JVM, not proof that the remote peer or storage device is the root cause.

### JIT and deoptimization

Repeated deoptimization, unusually long compilation, or heavy compiler activity can explain transient CPU/latency effects. Do not recommend compiler flags merely because compilation events exist.

### Virtual threads

Pinned-thread events are relevant when virtual threads are in use. Identify the pinning stack and duration. Do not infer virtual-thread pinning from ordinary monitor contention alone.

### Custom events

Use `event-types.txt` and `summary.txt` to identify non-JDK event types. Inspect custom events when their names or counts are relevant to the application. Preserve their domain semantics rather than forcing them into generic JVM categories.

## Output format

Return the analysis in this order:

1. **Conclusion**: 2-5 sentences describing the dominant performance signal and confidence.
2. **Findings**: prioritized as `Critical`, `High`, `Medium`, or `Low`. For each finding include:
   - evidence from the recording,
   - interpretation,
   - likely impact,
   - confidence (`high`, `medium`, `low`).
3. **What is not supported by the recording**: important claims the available events cannot establish.
4. **Recommended actions**: concrete code, configuration, or measurement steps, ordered by expected value.
5. **If another recording is needed**: state exactly which events/settings are missing and how to capture them.

Prefer measured quantities over vague adjectives. Include recording duration and JVM/JDK information when available. Never invent percentiles or durations that are not present in the reports.

## Better follow-up recordings

Only suggest a new recording when the existing one lacks evidence needed to answer the question. Prefer a bounded recording using the JDK `profile` configuration for performance investigation, for example:

```bash
jcmd <pid> JFR.start name=perf settings=profile duration=60s filename=recording.jfr
```

For suspected memory leaks, explain that old-object sampling / path-to-GC-roots may be needed and has additional cost. Do not enable expensive options casually in production.
