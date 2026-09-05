# JFR interpretation notes

Use these notes to avoid common false conclusions from Flight Recorder data.

## General

JFR is event-based. What can be concluded depends on which events were enabled, their thresholds, sampling period, recording duration, and workload during the recording. Absence of an event is not evidence that the behavior never occurred.

A short recording can be highly representative for a reproducible latency spike and nearly useless for a rare incident. Always relate conclusions to recording duration and workload context when known.

## CPU sampling

`ExecutionSample` / `hot-methods` is statistical sampling. Sample share approximates where runnable Java execution spent time during sampling, but it is not exact method timing. Very short methods and infrequent paths can be underrepresented.

High method sample share is strongest evidence when process/JVM CPU load is also high. Low JVM CPU plus a long wall-clock latency usually points toward waiting, blocking, I/O, scheduling, or an external dependency rather than a pure CPU hot spot.

Native samples may identify JNI, native libraries, runtime, or kernel-facing work that is not visible as Java method execution.

## Allocation versus leak

Allocation rate and allocation-by-site answer "where objects are created". They do not answer "what remains reachable".

Evidence consistent with a leak includes retained old objects, growing live set across repeated GC cycles, or corroborating heap-dump analysis. `OldObjectSample` is especially useful but may not be enabled or sufficiently sampled.

Do not recommend increasing heap as the first response to allocation pressure. It can reduce GC frequency while increasing worst-case collection work and may only conceal the source of churn.

## GC

Separate:

- GC throughput / CPU cost
- stop-the-world pause duration
- allocation pressure
- live-set / heap occupancy
- collector-specific concurrent work

A high GC count is not automatically bad. Many short collections can be preferable to fewer long pauses. Conversely, acceptable average pause time can hide a damaging maximum or tail.

Correlate long pauses with GC phase events and safepoints. Not every safepoint is a GC pause.

## Locks, parking, and waiting

`JavaMonitorEnter` indicates blocked monitor entry when it crosses the configured threshold. It is direct evidence of intrinsic-lock contention at the recorded site.

`ThreadPark` represents parking and can be caused by locks, queues, futures, executors, rate limiting, or deliberate coordination. Treat it as waiting evidence and inspect the stack before assigning a cause.

## Exceptions

Exception events can be expensive at high rates because throwing frequently constructs objects and often captures stack traces. But exception frequency alone does not prove an error. Framework control flow and expected parsing failures can produce legitimate exception traffic.

## File and socket I/O

Duration is observed blocking time around the JVM event. It can indicate storage, network, remote service, buffering, DNS, TLS, kernel scheduling, or application-level backpressure. Use host/path concentration and surrounding stacks to narrow the cause.

## JIT and deoptimization

Compilation is normal. Investigate unusually long compilations, heavy compiler CPU, code-cache pressure, or repeated deoptimizations associated with hot application code. A deoptimization event is diagnostic context, not itself a defect.

## Virtual-thread pinning

A pinned virtual thread prevents its carrier thread from being released while blocked. The stack and duration matter. Modern JDKs have improved pinning behavior, so interpret events in the context of the JDK version in the recording.

## Custom JFR events

Custom events can encode request IDs, domain operations, queue delays, state transitions, or application-specific durations. Their semantics come from the event definition, not from their name alone. If source code is available, inspect the event class before drawing conclusions from its fields.


## Package attribution

Package filtering should be stack-based. A package frame below the top frame means application code led to work currently executing elsewhere, such as collections, serialization, JDBC, socket I/O, or framework code. Report both the nearest package frame and the external leaf when useful.

Do not treat inclusive package attribution as self CPU time. Only samples whose top frame belongs to the package are direct package execution samples. Likewise, an I/O or blocking event attributed to a package call site shows where the operation was initiated or reached, not that the package itself caused storage, network, peer, or lock-owner latency.

A package report can be incomplete when event stack traces were disabled or truncated. If important signals have no usable stack, recommend a focused follow-up recording with `settings=profile` or explicitly enabled stack traces for the relevant event types.
