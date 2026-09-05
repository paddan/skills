import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Package-focused JFR analyzer. Runs in JDK source-file mode; no build tool or dependencies required.
 */
public class PackageJfrAnalyzer {
    private static final int DEFAULT_TOP = 20;

    private final Path recording;
    private final String packagePrefix;
    private final int top;

    private long totalEvents;
    private long packageStackEvents;
    private Instant firstEvent;
    private Instant lastEvent;

    private long cpuSamples;
    private long packageCpuSamples;
    private long directPackageCpuSamples;
    private final Map<String, Long> cpuSites = new LinkedHashMap<>();
    private final Map<String, Long> externalCpuLeaves = new LinkedHashMap<>();
    private final Map<String, Long> cpuThreads = new LinkedHashMap<>();
    private final Map<String, ClassStats> classOverview = new LinkedHashMap<>();

    private final Stats allocation = new Stats();
    private boolean allocationHasWeightedBytes;
    private final Map<String, Stats> allocationSites = new LinkedHashMap<>();
    private final Map<String, Stats> allocationClasses = new LinkedHashMap<>();

    private final Stats contention = new Stats();
    private final Map<String, Stats> contentionSites = new LinkedHashMap<>();
    private final Map<String, Stats> contentionTypes = new LinkedHashMap<>();
    private final Map<String, Stats> monitorClasses = new LinkedHashMap<>();

    private final Stats exceptions = new Stats();
    private final Map<String, Stats> exceptionSites = new LinkedHashMap<>();
    private final Map<String, Stats> exceptionClasses = new LinkedHashMap<>();

    private final Stats io = new Stats();
    private final Map<String, Stats> ioSites = new LinkedHashMap<>();
    private final Map<String, Stats> ioTypes = new LinkedHashMap<>();
    private final Map<String, Stats> ioTargets = new LinkedHashMap<>();

    private final Stats compilation = new Stats();
    private final Map<String, Stats> compilationMethods = new LinkedHashMap<>();
    private final Stats deoptimization = new Stats();
    private final Map<String, Stats> deoptimizationSites = new LinkedHashMap<>();

    private final Map<String, Stats> otherPackageEvents = new LinkedHashMap<>();

    private PackageJfrAnalyzer(Path recording, String packagePrefix, int top) {
        this.recording = recording;
        this.packagePrefix = normalizePackage(packagePrefix);
        this.top = top;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: java PackageJfrAnalyzer.java <recording.jfr> <package-prefix> [top-n]");
            System.exit(2);
        }
        int top = args.length == 3 ? Integer.parseInt(args[2]) : DEFAULT_TOP;
        if (top < 1 || top > 200) {
            throw new IllegalArgumentException("top-n must be between 1 and 200");
        }
        var analyzer = new PackageJfrAnalyzer(Path.of(args[0]), args[1], top);
        analyzer.analyze();
        analyzer.printReport();
    }

    private void analyze() throws IOException {
        try (var file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                totalEvents++;
                Instant start = event.getStartTime();
                if (firstEvent == null || start.isBefore(firstEvent)) firstEvent = start;
                Instant end = event.getEndTime();
                if (lastEvent == null || end.isAfter(lastEvent)) lastEvent = end;

                String type = event.getEventType().getName();
                List<RecordedFrame> frames = frames(event.getStackTrace());
                int packageIndex = firstPackageFrame(frames);
                boolean packageInStack = packageIndex >= 0;
                boolean packageMethodField = methodFieldBelongsToPackage(event);
                if (packageInStack) packageStackEvents++;

                switch (type) {
                    case "jdk.ExecutionSample", "jdk.NativeMethodSample" -> analyzeCpu(event, frames, packageIndex);
                    case "jdk.ObjectAllocationSample", "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" ->
                            analyzeAllocation(event, frames, packageIndex);
                    case "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark", "jdk.ThreadSleep" ->
                            analyzeContention(event, frames, packageIndex);
                    case "jdk.JavaExceptionThrow", "jdk.JavaErrorThrow" ->
                            analyzeException(event, frames, packageIndex);
                    case "jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileForce" ->
                            analyzeIo(event, frames, packageIndex);
                    case "jdk.Compilation" -> analyzeCompilation(event, frames, packageIndex, packageMethodField);
                    case "jdk.Deoptimization" -> analyzeDeoptimization(event, frames, packageIndex, packageMethodField);
                    default -> {
                        if (packageInStack || packageMethodField) {
                            stat(otherPackageEvents, type).add(eventDurationNanos(event), 0);
                        }
                    }
                }
            }
        }
    }

    private void analyzeCpu(RecordedEvent event, List<RecordedFrame> frames, int packageIndex) {
        cpuSamples++;
        if (packageIndex < 0) return;
        packageCpuSamples++;
        if (packageIndex == 0) directPackageCpuSamples++;
        RecordedFrame packageFrame = frames.get(packageIndex);
        increment(cpuSites, methodName(packageFrame));
        classStat(packageFrame).cpuSamples++;
        increment(cpuThreads, threadName(event));
        if (packageIndex > 0 && !frames.isEmpty()) {
            increment(externalCpuLeaves, methodName(frames.get(0)));
        }
    }

    private void analyzeAllocation(RecordedEvent event, List<RecordedFrame> frames, int packageIndex) {
        if (packageIndex < 0) return;
        long bytes = firstLong(event, "weight", "allocationSize");
        if (bytes > 0) allocationHasWeightedBytes = true;
        allocation.add(0, bytes);
        RecordedFrame packageFrame = frames.get(packageIndex);
        ClassStats classStats = classStat(packageFrame);
        classStats.allocationEvents++;
        classStats.allocationBytes += Math.max(bytes, 0);
        stat(allocationSites, methodName(packageFrame)).add(0, bytes);
        stat(allocationClasses, classValueName(event, "objectClass")).add(0, bytes);
    }

    private void analyzeContention(RecordedEvent event, List<RecordedFrame> frames, int packageIndex) {
        if (packageIndex < 0) return;
        long nanos = eventDurationNanos(event);
        contention.add(nanos, 0);
        RecordedFrame packageFrame = frames.get(packageIndex);
        ClassStats classStats = classStat(packageFrame);
        classStats.blockingEvents++;
        classStats.blockingNanos += Math.max(nanos, 0);
        stat(contentionSites, methodName(packageFrame)).add(nanos, 0);
        stat(contentionTypes, shortEventName(event)).add(nanos, 0);
        stat(monitorClasses, firstNonBlank(
                classValueName(event, "monitorClass"),
                classValueName(event, "parkedClass"),
                "(unknown)"
        )).add(nanos, 0);
    }

    private void analyzeException(RecordedEvent event, List<RecordedFrame> frames, int packageIndex) {
        if (packageIndex < 0) return;
        exceptions.add(0, 0);
        RecordedFrame packageFrame = frames.get(packageIndex);
        classStat(packageFrame).throwEvents++;
        stat(exceptionSites, methodName(packageFrame)).add(0, 0);
        stat(exceptionClasses, firstNonBlank(
                classValueName(event, "thrownClass"),
                classValueName(event, "throwableClass"),
                "(unknown)"
        )).add(0, 0);
    }

    private void analyzeIo(RecordedEvent event, List<RecordedFrame> frames, int packageIndex) {
        if (packageIndex < 0) return;
        long nanos = eventDurationNanos(event);
        long bytes = firstLong(event, "bytesRead", "bytesWritten", "bytes");
        io.add(nanos, bytes);
        RecordedFrame packageFrame = frames.get(packageIndex);
        ClassStats classStats = classStat(packageFrame);
        classStats.ioEvents++;
        classStats.ioNanos += Math.max(nanos, 0);
        classStats.ioBytes += Math.max(bytes, 0);
        stat(ioSites, methodName(packageFrame)).add(nanos, bytes);
        stat(ioTypes, shortEventName(event)).add(nanos, bytes);
        stat(ioTargets, ioTarget(event)).add(nanos, bytes);
    }

    private void analyzeCompilation(RecordedEvent event, List<RecordedFrame> frames, int packageIndex, boolean packageMethodField) {
        if (packageIndex < 0 && !packageMethodField) return;
        long nanos = eventDurationNanos(event);
        compilation.add(nanos, 0);
        String method = recordedMethodValue(event, "method");
        if (method.equals("(unknown)") && packageIndex >= 0) method = methodName(frames.get(packageIndex));
        stat(compilationMethods, method).add(nanos, 0);
    }

    private void analyzeDeoptimization(RecordedEvent event, List<RecordedFrame> frames, int packageIndex, boolean packageMethodField) {
        if (packageIndex < 0 && !packageMethodField) return;
        deoptimization.add(eventDurationNanos(event), 0);
        String method = recordedMethodValue(event, "method");
        if (method.equals("(unknown)") && packageIndex >= 0) method = methodName(frames.get(packageIndex));
        stat(deoptimizationSites, method).add(0, 0);
        if (packageIndex >= 0) classStat(frames.get(packageIndex)).deoptimizations++;
    }

    private void printReport() {
        System.out.println("# Package-focused JFR analysis");
        System.out.println();
        System.out.println("- Recording: `" + recording + "`");
        System.out.println("- Package prefix: `" + packagePrefix + "`");
        if (firstEvent != null && lastEvent != null) {
            System.out.println("- Observed event span: " + formatDuration(Duration.between(firstEvent, lastEvent)));
        }
        System.out.println("- Total JFR events scanned: " + totalEvents);
        System.out.println("- Events with this package in their recorded stack: " + packageStackEvents);
        System.out.println();
        System.out.println("> Package matching is inclusive of subpackages. Attribution uses the nearest matching frame to the sampled/blocked operation. If the top frame is outside the package, the operation is still attributed when the package appears lower in the stack.");

        printCpu();
        printClassOverview();
        printAllocation();
        printContention();
        printExceptions();
        printIo();
        printJit();
        printOtherEvents();

        System.out.println();
        System.out.println("## Interpretation constraints");
        System.out.println();
        System.out.println("- CPU data is sample-based. Percentages are shares of recorded execution/native samples, not exact CPU time.");
        System.out.println("- Allocation bytes are sampled/estimated for `ObjectAllocationSample` and should not be treated as retained heap or proof of a leak.");
        System.out.println("- Blocking/waiting and I/O duration show time observed in the recorded operation. Cumulative duration may exceed recording wall time when events overlap across threads. These durations do not by themselves prove the root cause is the lock owner, filesystem, or remote peer.");
        System.out.println("- Missing sections usually mean the required JFR event was disabled, below its threshold, or simply did not occur during the recording.");
    }

    private void printCpu() {
        System.out.println();
        System.out.println("## CPU samples");
        System.out.println();
        if (cpuSamples == 0) {
            System.out.println("No `ExecutionSample` or `NativeMethodSample` events were recorded.");
            return;
        }
        System.out.println("- Total CPU samples: " + cpuSamples);
        System.out.printf(Locale.ROOT, "- Samples with package in stack: %d (%.2f%% of all CPU samples)%n",
                packageCpuSamples, percent(packageCpuSamples, cpuSamples));
        if (packageCpuSamples > 0) {
            System.out.printf(Locale.ROOT, "- Samples executing directly in package at top frame: %d (%.2f%% of package-attributed samples)%n",
                    directPackageCpuSamples, percent(directPackageCpuSamples, packageCpuSamples));
            printLongTable("Hot package methods (nearest matching frame)", cpuSites, packageCpuSamples, "Samples");
            printLongTable("Threads carrying package-attributed CPU samples", cpuThreads, packageCpuSamples, "Samples");
            if (!externalCpuLeaves.isEmpty()) {
                printLongTable("External/JDK leaf methods executing above package code", externalCpuLeaves, packageCpuSamples, "Samples");
            }
        }
    }


    private void printClassOverview() {
        System.out.println();
        System.out.println("## Per-class activity overview");
        System.out.println();
        if (classOverview.isEmpty()) {
            System.out.println("No stack-attributed activity was recorded for classes in this package.");
            return;
        }
        System.out.println("This is an activity map, not a severity score. A class can legitimately dominate one column.");
        System.out.println();
        System.out.println("| Class | CPU samples | Alloc events | Alloc bytes | Blocking | Block time | Throws | I/O ops | I/O time | I/O bytes | Deopts |");
        System.out.println("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        classOverview.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, ClassStats>>comparingLong(e -> e.getValue().activityCount()).reversed())
                .limit(top)
                .forEach(e -> {
                    ClassStats s = e.getValue();
                    System.out.printf(Locale.ROOT,
                            "| `%s` | %d | %d | %s | %d | %s | %d | %d | %s | %s | %d |%n",
                            escape(e.getKey()), s.cpuSamples, s.allocationEvents, formatBytes(s.allocationBytes),
                            s.blockingEvents, formatNanos(s.blockingNanos), s.throwEvents, s.ioEvents,
                            formatNanos(s.ioNanos), formatBytes(s.ioBytes), s.deoptimizations);
                });
    }

    private void printAllocation() {
        System.out.println();
        System.out.println("## Allocation");
        System.out.println();
        if (allocation.count == 0) {
            System.out.println("No recorded allocation events had this package in their stack.");
            return;
        }
        System.out.println("- Package-attributed allocation events: " + allocation.count);
        if (allocationHasWeightedBytes) System.out.println("- Recorded/sample-weighted allocation bytes: " + formatBytes(allocation.bytes));
        printStatsTable("Allocation sites", allocationSites, allocation, true, false);
        printStatsTable("Allocated classes", allocationClasses, allocation, true, false);
    }

    private void printContention() {
        System.out.println();
        System.out.println("## Blocking, waits and contention");
        System.out.println();
        if (contention.count == 0) {
            System.out.println("No `JavaMonitorEnter`, `JavaMonitorWait`, `ThreadPark`, or `ThreadSleep` events had this package in their stack.");
            return;
        }
        System.out.println("- Package-attributed blocking events: " + contention.count);
        System.out.println("- Cumulative recorded duration: " + formatNanos(contention.nanos));
        System.out.println("- Longest recorded event: " + formatNanos(contention.maxNanos));
        printStatsTable("Blocking sites", contentionSites, contention, false, true);
        printStatsTable("Blocking event types", contentionTypes, contention, false, true);
        printStatsTable("Monitor/park classes", monitorClasses, contention, false, true);
    }

    private void printExceptions() {
        System.out.println();
        System.out.println("## Exceptions and errors");
        System.out.println();
        if (exceptions.count == 0) {
            System.out.println("No recorded Java exception/error throw events had this package in their stack.");
            return;
        }
        System.out.println("- Package-attributed throw events: " + exceptions.count);
        printStatsTable("Thrown classes", exceptionClasses, exceptions, false, false);
        printStatsTable("Throw sites", exceptionSites, exceptions, false, false);
    }

    private void printIo() {
        System.out.println();
        System.out.println("## File and socket I/O");
        System.out.println();
        if (io.count == 0) {
            System.out.println("No recorded file/socket I/O events had this package in their stack.");
            return;
        }
        System.out.println("- Package-attributed I/O events: " + io.count);
        System.out.println("- Cumulative recorded I/O duration: " + formatNanos(io.nanos));
        if (io.bytes > 0) System.out.println("- Recorded bytes: " + formatBytes(io.bytes));
        printStatsTable("I/O sites", ioSites, io, true, true);
        printStatsTable("I/O operation types", ioTypes, io, true, true);
        printStatsTable("I/O targets", ioTargets, io, true, true);
    }

    private void printJit() {
        System.out.println();
        System.out.println("## JIT / deoptimization");
        System.out.println();
        if (compilation.count == 0 && deoptimization.count == 0) {
            System.out.println("No compilation/deoptimization events could be attributed to package methods.");
            return;
        }
        if (compilation.count > 0) {
            System.out.println("- Package compilation events: " + compilation.count + ", cumulative duration " + formatNanos(compilation.nanos));
            printStatsTable("Compiled package methods", compilationMethods, compilation, false, true);
        }
        if (deoptimization.count > 0) {
            System.out.println("- Package deoptimization events: " + deoptimization.count);
            printStatsTable("Deoptimized package methods", deoptimizationSites, deoptimization, false, false);
        }
    }

    private void printOtherEvents() {
        if (otherPackageEvents.isEmpty()) return;
        System.out.println();
        System.out.println("## Other event types involving the package");
        System.out.println();
        System.out.println("These events had the package in their stack or directly referenced a package method. Treat them as leads, not automatically as problems.");
        printStatsTable(null, otherPackageEvents, null, false, true);
    }

    private void printLongTable(String title, Map<String, Long> map, long denominator, String valueHeader) {
        if (map.isEmpty()) return;
        System.out.println();
        System.out.println("### " + title);
        System.out.println();
        System.out.println("| " + valueHeader + " | Share | Method / item |");
        System.out.println("| ---: | ---: | --- |");
        sortedLongEntries(map).stream().limit(top).forEach(e ->
                System.out.printf(Locale.ROOT, "| %d | %.2f%% | `%s` |%n", e.getValue(), percent(e.getValue(), denominator), escape(e.getKey())));
    }

    private void printStatsTable(String title, Map<String, Stats> map, Stats denominator, boolean showBytes, boolean showDuration) {
        if (map.isEmpty()) return;
        if (title != null) {
            System.out.println();
            System.out.println("### " + title);
            System.out.println();
        }
        StringBuilder header = new StringBuilder("| Count |");
        StringBuilder sep = new StringBuilder("| ---: |");
        if (showDuration) { header.append(" Duration | Max | "); sep.append(" ---: | ---: | "); }
        if (showBytes) { header.append(" Bytes | "); sep.append(" ---: | "); }
        header.append("Item |");
        sep.append("--- |");
        System.out.println(header);
        System.out.println(sep);

        Comparator<Map.Entry<String, Stats>> cmp;
        if (showDuration) {
            cmp = Comparator.<Map.Entry<String, Stats>>comparingLong(e -> e.getValue().nanos).reversed()
                    .thenComparing(Comparator.comparingLong((Map.Entry<String, Stats> e) -> e.getValue().count).reversed());
        } else if (showBytes) {
            cmp = Comparator.<Map.Entry<String, Stats>>comparingLong(e -> e.getValue().bytes).reversed()
                    .thenComparing(Comparator.comparingLong((Map.Entry<String, Stats> e) -> e.getValue().count).reversed());
        } else {
            cmp = Comparator.<Map.Entry<String, Stats>>comparingLong(e -> e.getValue().count).reversed();
        }

        map.entrySet().stream().sorted(cmp).limit(top).forEach(e -> {
            Stats s = e.getValue();
            StringBuilder row = new StringBuilder("| ").append(s.count).append(" |");
            if (showDuration) row.append(' ').append(formatNanos(s.nanos)).append(" | ").append(formatNanos(s.maxNanos)).append(" |");
            if (showBytes) row.append(' ').append(formatBytes(s.bytes)).append(" |");
            row.append(" `").append(escape(e.getKey())).append("` |");
            System.out.println(row);
        });
    }

    private int firstPackageFrame(List<RecordedFrame> frames) {
        for (int i = 0; i < frames.size(); i++) {
            String className = className(frames.get(i));
            if (belongsToPackage(className)) return i;
        }
        return -1;
    }

    private boolean methodFieldBelongsToPackage(RecordedEvent event) {
        Object value = safeValue(event, "method");
        return value instanceof RecordedMethod method && belongsToPackage(method.getType().getName());
    }

    private boolean belongsToPackage(String className) {
        if (className == null) return false;
        String normalized = className.replace('/', '.');
        return normalized.startsWith(packagePrefix + ".");
    }

    private static String normalizePackage(String input) {
        String p = input.trim().replace('/', '.');
        while (p.endsWith(".*")) p = p.substring(0, p.length() - 2);
        while (p.endsWith(".")) p = p.substring(0, p.length() - 1);
        if (p.isBlank() || p.contains(" ")) throw new IllegalArgumentException("Invalid package prefix: " + input);
        return p;
    }

    private static List<RecordedFrame> frames(RecordedStackTrace trace) {
        return trace == null ? List.of() : trace.getFrames();
    }

    private static String methodName(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        String type = method.getType().getName().replace('/', '.');
        int line = frame.getLineNumber();
        return type + "." + method.getName() + (line > 0 ? ":" + line : "");
    }

    private static String recordedMethodValue(RecordedEvent event, String field) {
        Object value = safeValue(event, field);
        if (value instanceof RecordedMethod method) {
            return method.getType().getName().replace('/', '.') + "." + method.getName();
        }
        return "(unknown)";
    }

    private static String className(RecordedFrame frame) {
        return frame.getMethod().getType().getName().replace('/', '.');
    }

    private static String classValueName(RecordedEvent event, String field) {
        Object value = safeValue(event, field);
        if (value instanceof RecordedClass rc) return rc.getName().replace('/', '.');
        return "(unknown)";
    }

    private static Object safeValue(RecordedEvent event, String field) {
        try {
            return event.getValue(field);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static long firstLong(RecordedEvent event, String... fields) {
        for (String field : fields) {
            Object value = safeValue(event, field);
            if (value instanceof Number n) return n.longValue();
        }
        return 0;
    }

    private static long eventDurationNanos(RecordedEvent event) {
        Duration duration = event.getDuration();
        return duration == null ? 0 : duration.toNanos();
    }

    private static String threadName(RecordedEvent event) {
        RecordedThread thread = event.getThread();
        if (thread == null) {
            for (String field : new String[]{"sampledThread", "eventThread", "thread"}) {
                Object value = safeValue(event, field);
                if (value instanceof RecordedThread rt) {
                    thread = rt;
                    break;
                }
            }
        }
        if (thread == null) return "(unknown)";
        String javaName = thread.getJavaName();
        String osName = thread.getOSName();
        if (javaName != null && !javaName.isBlank()) return javaName;
        return osName == null || osName.isBlank() ? "(unknown)" : osName;
    }

    private static String ioTarget(RecordedEvent event) {
        for (String field : new String[]{"path", "host", "address", "remoteAddress"}) {
            Object value = safeValue(event, field);
            if (value != null) return String.valueOf(value);
        }
        return "(unknown)";
    }

    private static String shortEventName(RecordedEvent event) {
        String name = event.getEventType().getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank() && !value.equals("(unknown)")) return value;
        return values.length == 0 ? "(unknown)" : values[values.length - 1];
    }

    private static Stats stat(Map<String, Stats> map, String key) {
        return map.computeIfAbsent(key == null || key.isBlank() ? "(unknown)" : key, ignored -> new Stats());
    }

    private static void increment(Map<String, Long> map, String key) {
        map.merge(key == null ? "(unknown)" : key, 1L, Long::sum);
    }

    private static List<Map.Entry<String, Long>> sortedLongEntries(Map<String, Long> map) {
        var list = new ArrayList<>(map.entrySet());
        list.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        return list;
    }

    private ClassStats classStat(RecordedFrame frame) {
        return classOverview.computeIfAbsent(className(frame), ignored -> new ClassStats());
    }

    private static double percent(long part, long total) {
        return total == 0 ? 0.0 : 100.0 * part / total;
    }

    private static String formatDuration(Duration d) {
        return formatNanos(d.toNanos());
    }

    private static String formatNanos(long nanos) {
        if (nanos <= 0) return "0";
        if (nanos >= 1_000_000_000L) return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0);
        if (nanos >= 1_000_000L) return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
        if (nanos >= 1_000L) return String.format(Locale.ROOT, "%.3f us", nanos / 1_000.0);
        return nanos + " ns";
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    private static String escape(String s) {
        return s.replace("|", "\\|").replace("`", "'").replace("\n", " ").replace("\r", " ");
    }

    private static final class ClassStats {
        long cpuSamples;
        long allocationEvents;
        long allocationBytes;
        long blockingEvents;
        long blockingNanos;
        long throwEvents;
        long ioEvents;
        long ioNanos;
        long ioBytes;
        long deoptimizations;

        long activityCount() {
            return cpuSamples + allocationEvents + blockingEvents + throwEvents + ioEvents + deoptimizations;
        }
    }

    private static final class Stats {
        long count;
        long nanos;
        long bytes;
        long maxNanos;

        void add(long durationNanos, long byteCount) {
            count++;
            nanos += Math.max(durationNanos, 0);
            bytes += Math.max(byteCount, 0);
            maxNanos = Math.max(maxNanos, durationNanos);
        }
    }
}
