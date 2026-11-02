package com.jfx.git.ui;

import com.jfx.git.ui.syntax.HighlightLane;

import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How long the diff viewer takes to show a file, and to colour it, on inputs large enough
 * for the answer to mean something.
 *
 * <h2>Why this exists</h2>
 * The UI review asked for latency and memory figures before any claim about
 * responsiveness, and nothing measured them: virtualization says the viewer <em>should</em>
 * be fast, not that it is. This records, for a large source file, a large diff and a
 * minified file:
 * <ul>
 *   <li><b>render</b> - from handing the viewer the bytes to the text being laid out, which
 *       is what the FX thread is blocked for;</li>
 *   <li><b>coloured</b> - until the syntax colours are on screen as well, which happens off
 *       the FX thread and is what the reader waits for.</li>
 * </ul>
 * p50 and p95 over repeated runs, and the peak heap, go to
 * {@code target/perf/diff-viewer.csv} so that a baseline can be kept and compared across
 * changes on the same machine.
 *
 * <h2>What the budgets mean</h2>
 * The same as {@code GraphPerformanceTest}'s: loose tripwires, several times the observed
 * cost, for the kind of regression that turns a render into a stall - not a benchmark.
 * These are headless, software-rendered timings; native captures on the target machines
 * are still the evidence for the user-facing numbers.
 *
 * <p>Tagged {@code slow}: {@code mvn test -Dgroups=slow -DexcludedTestGroups=}
 */
@Tag("slow")
class DiffViewerPerformanceTest {

    private static final int RUNS = 15;
    /**
     * The FX thread's share. Set from the first baseline (26 September 2026, headless
     * software rendering): an 18,000-row diff held the thread for about 1.4s at p50 and
     * 1.8s at p95, 3.6s cold. That is already a visible freeze, and it is recorded as a
     * finding rather than hidden under this number; the budget is about three times the
     * steady cost, a tripwire for it getting worse.
     */
    private static final long RENDER_BUDGET_MILLIS = 6_000;
    /** Until the colours land; the text is readable before then. */
    private static final long COLOURED_BUDGET_MILLIS = 8_000;

    private static final List<String> REPORT = new ArrayList<>();

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
        REPORT.add("case,runs,render_p50_ms,render_p95_ms,coloured_p50_ms,coloured_p95_ms,retained_heap_mb");
        HighlightLane.setSynchronousForTest(false);
    }

    @AfterAll
    static void writeReport() throws IOException {
        HighlightLane.setSynchronousForTest(true);
        Path out = Path.of("target", "perf", "diff-viewer.csv");
        Files.createDirectories(out.getParent());
        Files.write(out, REPORT, StandardCharsets.UTF_8);
        System.out.println("Diff viewer timings written to " + out.toAbsolutePath());
        REPORT.forEach(System.out::println);
    }

    @AfterEach
    void closeStages() {
        FxHarness.closeShownStages();
    }

    /** The viewer's own source: thousands of lines of real Java, the grammar's worst case here. */
    @Test
    void aLargeJavaFile() throws Exception {
        byte[] source = Files.readAllBytes(Path.of("src/main/java/com/jfx/git/ui/DiffViewer.java"));
        measure("large-java-file", viewer -> viewer.showFileBytes("DiffViewer.java", source));
    }

    /** Twenty thousand rows of unified diff, spread over many hunks. */
    @Test
    void aTwentyThousandRowDiff() throws Exception {
        StringBuilder diff = new StringBuilder();
        int line = 1;
        for (int hunk = 0; hunk < 2_000; hunk++) {
            diff.append("@@ -").append(line).append(",8 +").append(line).append(",8 @@\n");
            for (int i = 0; i < 3; i++) {
                diff.append("     int context").append(line + i).append(" = ").append(i).append(";\n");
            }
            diff.append("-    String before").append(hunk).append(" = \"old\";\n");
            diff.append("+    String after").append(hunk).append(" = \"new\";\n");
            for (int i = 0; i < 3; i++) {
                diff.append("     int trailing").append(line + i).append(" = ").append(i).append(";\n");
            }
            line += 20;
        }
        byte[] bytes = diff.toString().getBytes(StandardCharsets.UTF_8);
        measure("20k-row-diff", viewer -> viewer.setFileDiffBytes("Big.java", bytes));
    }

    /** One line, three hundred kilobytes: what the character budget exists for. */
    @Test
    void aMinifiedFile() throws Exception {
        byte[] minified = ("var a=1,b=function(c){return c+1};".repeat(10_000) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        measure("minified-js", viewer -> viewer.showFileBytes("app.min.js", minified));
    }

    private interface Show {
        void on(DiffViewer viewer);
    }

    private void measure(String name, Show show) throws Exception {
        List<Long> render = new ArrayList<>();
        List<Long> coloured = new ArrayList<>();
        resetPeaks();
        for (int run = 0; run < RUNS; run++) {
            DiffViewer viewer = FxHarness.call(() -> {
                DiffViewer v = new DiffViewer();
                Stage stage = FxHarness.show(v, 1100, 800);
                stage.setUserData("keep-alive");
                return v;
            });
            long started = System.nanoTime();
            FxHarness.run(() -> {
                show.on(viewer);
                FxHarness.pulse(viewer);
            });
            long rendered = System.nanoTime();
            HighlightLane.awaitIdleForTest();
            // The lane's answer is queued on the FX thread; this runs after it.
            FxHarness.run(() -> FxHarness.pulse(viewer));
            long done = System.nanoTime();
            render.add((rendered - started) / 1_000_000);
            coloured.add((done - started) / 1_000_000);
            sampleHeap();
            FxHarness.closeShownStages();
        }
        // The first run pays for loading the grammar and warming the JIT; the figures that
        // describe steady use leave it out, but the budget covers it too.
        long firstRender = render.get(0);
        long firstColoured = coloured.get(0);
        List<Long> steadyRender = render.subList(1, render.size());
        List<Long> steadyColoured = coloured.subList(1, coloured.size());
        REPORT.add(String.join(",", name, String.valueOf(RUNS - 1),
                String.valueOf(percentile(steadyRender, 50)), String.valueOf(percentile(steadyRender, 95)),
                String.valueOf(percentile(steadyColoured, 50)), String.valueOf(percentile(steadyColoured, 95)),
                String.valueOf(peakHeapMegabytes())));

        assertTrue(Math.max(firstRender, percentile(steadyRender, 95)) < RENDER_BUDGET_MILLIS,
                name + ": the FX thread was held for " + render + " ms");
        assertTrue(Math.max(firstColoured, percentile(steadyColoured, 95)) < COLOURED_BUDGET_MILLIS,
                name + ": colours took " + coloured + " ms");
    }

    private static long percentile(List<Long> values, int percentile) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** The most heap still live after a run; see {@link #sampleHeap()}. */
    private static long peakHeapBytes;

    private static void resetPeaks() {
        System.gc();
        peakHeapBytes = 0;
    }

    /**
     * Heap still live with the viewer on screen, after a collection - what the viewer
     * holds on to, not how much garbage it made on the way. Sampled rather than read from
     * the JVM's peak counters: those live in java.management, which the application module
     * does not read, and a test is no reason to add it.
     */
    private static void sampleHeap() {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        peakHeapBytes = Math.max(peakHeapBytes, runtime.totalMemory() - runtime.freeMemory());
    }

    private static long peakHeapMegabytes() {
        return peakHeapBytes / (1024 * 1024);
    }
}
