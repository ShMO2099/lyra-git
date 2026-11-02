package com.jfx.git.ui;

import com.jfx.git.core.GitService;

import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every way the diff viewer can be configured, against every kind of diff it is shown.
 *
 * <h2>Why this exists</h2>
 * The viewer has six independent switches - two layouts, whole-file, diff/file view,
 * ignore-whitespace, show-whitespace and wrap - and three sources of content: an unstaged
 * change, a staged one, and a commit. The existing render tests check one combination at a
 * time, and they check <em>structure</em>: how many surfaces exist, whether two columns
 * have the same number of rows. That is what let split view ship broken -
 * {@code splitViewRendersTwoAlignedColumns} asserts
 * {@code left.getParagraphs().size() == right.getParagraphs().size()}, which two empty
 * columns satisfy perfectly.
 *
 * <p>So every assertion here is about <em>content</em>: the line the diff actually changed
 * has to be readable on screen, in the column it belongs to. A viewer that renders two
 * beautifully aligned empty columns fails every test in this class.
 */
@Tag("heavy")
class DiffViewerMatrixTest {

    // ------------------------------------------------------------------
    // The fixture: one file, three kinds of pending change
    // ------------------------------------------------------------------

    /**
     * A line nowhere near any hunk, so Whole file can be told apart from a plain diff.
     *
     * <p>Declared before {@link #V1} because {@code V1} is built from it, and static
     * fields initialise in declaration order.
     */
    private static final String FAR_FROM_ANY_HUNK = "int epsilon = 5;";

    /**
     * Committed at HEAD~1, so there is a commit whose diff can be asked for.
     *
     * <p>The filler in the middle is load-bearing: a diff shows three lines of context
     * around each hunk, so "Whole file revealed something the hunks did not" is only a
     * real assertion if there is a line far enough from every hunk to be outside all of
     * them. In a fifteen-line file there is no such line, and the test passes without
     * testing anything.
     */
    private static final String V1 = buildV1();

    private static String buildV1() {
        StringBuilder sb = new StringBuilder("package demo;\n\npublic class Sample {\n");
        sb.append("    int alpha = 1;\n");
        sb.append("    int beta = 2;\n");
        sb.append("    int gamma = 3;\n");
        for (int i = 0; i < 40; i++) {
            sb.append("    int filler").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("    ").append(FAR_FROM_ANY_HUNK).append('\n');
        for (int i = 40; i < 80; i++) {
            sb.append("    int filler").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("    int kappa = 10;\n}\n");
        return sb.toString();
    }

    /** HEAD: gamma changed. This is the diff a commit row shows. */
    private static final String V2 = V1.replace("int gamma = 3;", "int gamma = 33;");

    /** Index: beta changed on top of HEAD. This is the staged diff. */
    private static final String V3 = V2.replace("int beta = 2;", "int beta = 22;");

    /** Work tree: kappa changed on top of the index. This is the unstaged diff. */
    private static final String V4 = V3.replace("int kappa = 10;", "int kappa = 100;");

    private static final String PATH = "Sample.java";

    private static Path repo;
    private static GitService service;
    private static String headHash;

    /**
     * A fresh repository for every test.
     *
     * <p>Was {@code @BeforeAll}, which is how the first run of this class produced eight
     * failures that had nothing to do with the viewer: one test committed into the shared
     * fixture, which folded the staged change into HEAD, and every later test asking for
     * "the staged diff" correctly got nothing. A fixture that tests can destroy for each
     * other reports bugs in whichever test happens to run second.
     */
    @BeforeEach
    void buildFixture() throws Exception {
        tearDown();
        repo = Files.createTempDirectory("diff-matrix");
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(repo.toFile());

        write(PATH, V1);
        assertTrue(service.stageFile(PATH).isOk());
        assertTrue(service.commit("add Sample", false).isOk());

        write(PATH, V2);
        assertTrue(service.stageFile(PATH).isOk());
        assertTrue(service.commit("change gamma", false).isOk());
        headHash = service.getHeadHash();

        write(PATH, V3);
        assertTrue(service.stageFile(PATH).isOk());
        write(PATH, V4);

        // The fixture is only worth anything if git agrees it is what it claims to be.
        var state = service.getStagingState();
        assertTrue(state.staged().contains(PATH), "beta should be staged");
        assertTrue(state.unstaged().contains(PATH), "kappa should be unstaged");
    }

    /**
     * Stages this class opened, so it can close them again.
     *
     * <p>Leaving them showing is not free. Each carries a live RichTextFX area, and with
     * hunk handlers installed a set of hunk-action bars on top of it; fifty-four of those
     * accumulating through one class slowed the JVM enough that the fixed sleeps in the
     * robot tests running afterwards stopped being long enough, and CherryPickRobotTest
     * began failing on clicks that landed before its dialog had caught up. A test that
     * leaves windows open is charging its cost to whatever runs next.
     */
    private final List<Stage> openedStages = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // On the FX thread: hiding a window is a toolkit call and tearDown runs on the
        // test thread.
        FxHarness.run(() -> {
            openedStages.forEach(Stage::hide);
            openedStages.clear();
        });
        if (service != null) {
            service.close();
            service = null;
        }
    }

    private static void write(String name, String content) throws Exception {
        Files.writeString(repo.resolve(name), content, StandardCharsets.UTF_8);
    }

    /**
     * The three places a diff comes from, and the one line each of them changed.
     *
     * @param removed the "before" line, which belongs on the left in split view
     * @param added   the "after" line, which belongs on the right
     */
    private record Source(String name, java.util.function.Supplier<byte[]> diff,
                          String removed, String added) {
        @Override public String toString() {
            return name;
        }
    }

    private static Source unstaged() {
        return new Source("unstaged",
                () -> service.getUnstagedDiffBytes(PATH, false),
                "int kappa = 10;", "int kappa = 100;");
    }

    private static Source staged() {
        return new Source("staged",
                () -> service.getStagedDiffBytes(PATH, false),
                "int beta = 2;", "int beta = 22;");
    }

    private static Source committed() {
        return new Source("committed",
                () -> service.getDiffBytesForFileInCommit(headHash, PATH, false),
                "int gamma = 3;", "int gamma = 33;");
    }

    static Stream<Source> sources() {
        return Stream.of(unstaged(), staged(), committed());
    }

    /** Every source crossed with every layout, and with Whole file off and on. */
    static Stream<Arguments> sourcesAndLayouts() {
        List<Arguments> all = new ArrayList<>();
        for (Source source : sources().toList()) {
            for (String layout : List.of("Unified", "Split")) {
                for (boolean wholeFile : List.of(false, true)) {
                    all.add(Arguments.of(source, layout, wholeFile));
                }
            }
        }
        return all.stream();
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /** A viewer on a laid-out stage, showing {@code source}, able to expand to whole file. */
    private DiffViewer viewerFor(Source source) {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, 1100, 700);
            openedStages.add(stage);
            // Whole file is only offered when the viewer can read the file behind the
            // diff; without this the toggle is disabled and half the matrix is untestable.
            viewer.setFileContentSupplier(path -> V4.getBytes(StandardCharsets.UTF_8));

            // A hunk-action runner and a diff kind that offers actions, because
            // layoutHunkActions returns before doing anything otherwise. Without them every
            // combination below rendered its rows and silently skipped the overlay that
            // sits on top of them - which is how a crash inside hunk placement survived
            // fifty-four passing cases. The runner does nothing; installing it is the point.
            viewer.setOnHunkAction(action -> { });
            viewer.setFileDiffBytes(DiffKind.UNSTAGED, PATH, source.diff().get());
            FxHarness.pulse(viewer);
            return viewer;
        });
    }

    private static List<DiffTextArea> surfaces(DiffViewer viewer) {
        List<DiffTextArea> areas = new ArrayList<>();
        for (Node node : FxHarness.lookupAll(viewer, "code-area")) {
            // Split leaves the unified surface built but detached; only what is actually
            // in the scene counts as "on screen".
            if (node instanceof DiffTextArea area && area.getScene() != null
                    && isInScene(area)) {
                areas.add(area);
            }
        }
        return areas;
    }

    /** Whether every ancestor is visible, so the area is genuinely being shown. */
    private static boolean isInScene(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    private static String textOf(DiffTextArea area) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < area.getParagraphs().size(); i++) {
            sb.append(area.getParagraph(i).getText()).append('\n');
        }
        return sb.toString();
    }

    /** Everything on screen, both columns included. */
    private static String allText(DiffViewer viewer) {
        StringBuilder sb = new StringBuilder();
        for (DiffTextArea area : surfaces(viewer)) {
            sb.append(textOf(area));
        }
        return sb.toString();
    }

    /**
     * The same rows with any whitespace markers undone.
     *
     * <p>Showing whitespace substitutes middots and arrows into the displayed text, so
     * the display is the wrong thing to compare when asking whether the <em>content</em>
     * changed. {@code sourceLine} is what the rest of the viewer reads.
     */
    private static String allSourceText(DiffViewer viewer) {
        StringBuilder sb = new StringBuilder();
        for (DiffTextArea area : surfaces(viewer)) {
            for (int i = 0; i < area.getParagraphs().size(); i++) {
                sb.append(area.sourceLine(i)).append('\n');
            }
        }
        return sb.toString();
    }

    private static void toggle(DiffViewer viewer, String label) {
        for (String styleClass : List.of("diff-segment", "diff-tool-toggle")) {
            for (Node node : FxHarness.lookupAll(viewer, styleClass)) {
                if (node instanceof ToggleButton button && (label.equals(button.getText())
                        || label.equals(button.getAccessibleText()))) {
                    button.fire();
                    return;
                }
            }
        }
        throw new AssertionError("no toggle labelled " + label);
    }

    // ------------------------------------------------------------------
    // The invariant the structural tests missed
    // ------------------------------------------------------------------

    /**
     * Whatever the layout, the change has to be readable on screen.
     *
     * <p>This is the test that fails when a surface renders its gutter and no text.
     */
    @ParameterizedTest(name = "{0} · {1} · wholeFile={2}")
    @MethodSource("sourcesAndLayouts")
    void theChangedLinesAreOnScreen(Source source, String layout, boolean wholeFile) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, layout);
            if (wholeFile) toggle(viewer, "All");
            FxHarness.pulse(viewer);

            List<DiffTextArea> areas = surfaces(viewer);
            assertEquals("Split".equals(layout) ? 2 : 1, areas.size(),
                    layout + " should show that many surfaces");

            for (DiffTextArea area : areas) {
                assertFalse(area.getParagraphs().isEmpty(),
                        "a surface rendered no rows at all - this is the empty-column bug");
            }

            String shown = allText(viewer);
            assertTrue(shown.contains(source.removed()),
                    () -> "the removed line should be readable: " + describe(shown));
            assertTrue(shown.contains(source.added()),
                    () -> "the added line should be readable: " + describe(shown));
        });
    }

    /** In split, each side of the change belongs in its own column. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void splitPutsBeforeOnTheLeftAndAfterOnTheRight(Source source) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);

            List<DiffTextArea> areas = surfaces(viewer);
            assertEquals(2, areas.size(), "split view must render both columns");
            String left = textOf(areas.get(0));
            String right = textOf(areas.get(1));

            assertTrue(left.contains(source.removed()),
                    () -> "the before line belongs on the left: " + describe(left));
            assertTrue(right.contains(source.added()),
                    () -> "the after line belongs on the right: " + describe(right));
            assertFalse(left.contains(source.added()),
                    "the left column is the old revision; the new line has no place in it");
            assertFalse(right.contains(source.removed()),
                    "the right column is the new revision; the old line has no place in it");
        });
    }

    /**
     * Both columns have to be wide enough to read.
     *
     * <p>Content reaching the text areas is not the same as content reaching the user.
     * The two columns are {@code HBox} children that both grow, and a surface whose
     * preferred width follows its longest line can take the whole row and squeeze the
     * other one to a strip - rows, gutters and all correctly rendered, into a column too
     * narrow to show a character of code. That is indistinguishable from "split view is
     * broken" when looking at the window.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void splitGivesBothColumnsAUsableShareOfTheWidth(Source source) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);

            List<DiffTextArea> areas = surfaces(viewer);
            assertEquals(2, areas.size());
            double left = areas.get(0).getWidth();
            double right = areas.get(1).getWidth();

            assertTrue(left > 100, "the left column is " + left + "px wide - unreadable");
            assertTrue(right > 100, "the right column is " + right + "px wide - unreadable");
            // Not an exact halving: the columns carry different content and the right one
            // owns the scrollbar. But a ten-to-one split is a layout failure, not a
            // preference.
            double ratio = Math.max(left, right) / Math.min(left, right);
            assertTrue(ratio < 3,
                    () -> "the columns should share the row; got " + left + " vs " + right);
        });
    }

    /**
     * A long line must not let one column eat the row.
     *
     * <p>The fixture's ordinary lines are short. This is the case that actually bites:
     * real code has lines hundreds of characters long, and if the surface's preferred
     * width follows them the layout has to clamp it rather than hand it the row.
     */
    @Test
    void aVeryLongLineDoesNotCollapseTheOtherColumn() throws Exception {
        String longLine = "        int wide = " + "1234567890".repeat(30) + ";";
        write(PATH, V4.replace("    int alpha = 1;", longLine));
        Source source = unstaged();

        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);

            List<DiffTextArea> areas = surfaces(viewer);
            assertEquals(2, areas.size());
            double left = areas.get(0).getWidth();
            double right = areas.get(1).getWidth();
            assertTrue(left > 100 && right > 100,
                    () -> "a long line collapsed a column: " + left + " vs " + right);
        });
    }

    /** Both columns stay row-for-row aligned, and are not empty while doing it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void splitColumnsAreAlignedAndPopulated(Source source) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);

            List<DiffTextArea> areas = surfaces(viewer);
            int left = areas.get(0).getParagraphs().size();
            int right = areas.get(1).getParagraphs().size();
            assertEquals(left, right, "the columns must stay row for row in step");
            assertTrue(left > 0, "two empty columns are aligned and useless");
        });
    }

    // ------------------------------------------------------------------
    // Whole file
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} · {1}")
    @MethodSource("sourcesAndLayoutsOnly")
    void wholeFileRevealsLinesTheHunksDoNotReach(Source source, String layout) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, layout);
            FxHarness.pulse(viewer);
            boolean beforeExpanding = allText(viewer).contains(FAR_FROM_ANY_HUNK);

            toggle(viewer, "All");
            FxHarness.pulse(viewer);
            String expanded = allText(viewer);

            assertTrue(expanded.contains(FAR_FROM_ANY_HUNK),
                    () -> "whole file should reach " + FAR_FROM_ANY_HUNK + ": " + describe(expanded));
            assertTrue(expanded.contains(source.added()),
                    "and must not lose the change while doing it");
            // The fixture is built so this line is outside the default context; if that
            // ever stops being true the test above stops proving anything.
            assertFalse(beforeExpanding,
                    "fixture problem: the line is already visible without Whole file");
        });
    }

    static Stream<Arguments> sourcesAndLayoutsOnly() {
        List<Arguments> all = new ArrayList<>();
        for (Source source : sources().toList()) {
            for (String layout : List.of("Unified", "Split")) {
                all.add(Arguments.of(source, layout));
            }
        }
        return all.stream();
    }

    // ------------------------------------------------------------------
    // File view
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void fileViewShowsTheWholeFileWithoutDiffMarkers(Source source) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, "File view");
            FxHarness.pulse(viewer);

            assertTrue(viewer.isFileView());
            String shown = allText(viewer);
            assertTrue(shown.contains("package demo;"),
                    () -> "file view shows the file: " + describe(shown));
            assertTrue(shown.contains(FAR_FROM_ANY_HUNK), "including the parts nothing changed");
            assertFalse(shown.contains("@@ "),
                    "file view is the file, not the diff scaffolding around it");
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void returningToDiffViewRestoresTheDiff(Source source) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            toggle(viewer, "Diff view");
            FxHarness.pulse(viewer);

            assertFalse(viewer.isFileView());
            String shown = allText(viewer);
            assertTrue(shown.contains(source.added()),
                    () -> "the diff should be back: " + describe(shown));
        });
    }

    /** Split survives a trip through File view, in both directions. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void splitSurvivesARoundTripThroughFileView(Source source) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);
            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            assertEquals(1, surfaces(viewer).size(), "file view is one surface, not two");

            toggle(viewer, "Diff view");
            FxHarness.pulse(viewer);

            List<DiffTextArea> areas = surfaces(viewer);
            assertEquals(2, areas.size(), "the split layout should come back");
            assertTrue(textOf(areas.get(0)).contains(source.removed()),
                    "and come back populated");
            assertTrue(textOf(areas.get(1)).contains(source.added()));
        });
    }

    // ------------------------------------------------------------------
    // Wrap
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} · {1}")
    @MethodSource("sourcesAndLayoutsOnly")
    void wrapAppliesToEverySurfaceAndKeepsTheContent(Source source, String layout) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, layout);
            FxHarness.pulse(viewer);
            for (DiffTextArea area : surfaces(viewer)) {
                assertFalse(area.isWrapText(), "wrap starts off");
            }

            toggle(viewer, DiffViewer.NAME_WRAP);
            FxHarness.pulse(viewer);

            List<DiffTextArea> areas = surfaces(viewer);
            assertFalse(areas.isEmpty(), "wrapping must not empty the viewer");
            for (DiffTextArea area : areas) {
                assertTrue(area.isWrapText(),
                        "every visible surface should wrap, not just the first");
            }
            assertTrue(allText(viewer).contains(source.added()),
                    "wrapping changes the layout, not the content");
        });
    }

    // ------------------------------------------------------------------
    // Whitespace: the two switches that are easy to confuse
    // ------------------------------------------------------------------

    /**
     * Show-whitespace is a display setting: the same rows, drawn with markers.
     *
     * <p>It must never change what the diff says, which is the thing that makes it a
     * different switch from ignore-whitespace.
     */
    @ParameterizedTest(name = "{0} · {1}")
    @MethodSource("sourcesAndLayoutsOnly")
    void showingWhitespaceMarksTheDisplayWithoutChangingTheSource(Source source, String layout) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            toggle(viewer, layout);
            FxHarness.pulse(viewer);
            String sourceBefore = allSourceText(viewer);
            assertFalse(allText(viewer).contains(WhitespaceMapping.SPACE_MARKER),
                    "markers start off");

            toggle(viewer, DiffViewer.NAME_SHOW_WHITESPACE);
            FxHarness.pulse(viewer);

            // The displayed text genuinely changes - that is the whole feature, spaces
            // are drawn as middots. What must not change is the text underneath it,
            // which is what every other part of the viewer reads.
            assertTrue(allText(viewer).contains(WhitespaceMapping.SPACE_MARKER),
                    "the indented fixture should now show space markers");
            assertEquals(sourceBefore, allSourceText(viewer),
                    "drawing spaces is a display choice; the source rows must be unchanged");
            assertTrue(allSourceText(viewer).contains(source.added()),
                    "and the change is still there underneath");
        });
    }

    /**
     * Ignore-whitespace recomputes the diff, so the viewer delegates rather than
     * re-rendering: the host owns the diff and has to be told to fetch a new one.
     */
    @Test
    void ignoreWhitespaceAsksTheHostForAFreshDiff() {
        DiffViewer viewer = viewerFor(unstaged());
        FxHarness.run(() -> {
            List<Boolean> asked = new ArrayList<>();
            viewer.setOnIgnoreWhitespaceChanged(asked::add);

            toggle(viewer, DiffViewer.NAME_IGNORE_WHITESPACE);
            FxHarness.pulse(viewer);

            assertEquals(List.of(true), asked,
                    "the host must be told, because -w changes what git reports");
            assertTrue(viewer.isIgnoreWhitespace());
        });
    }

    /**
     * And the recomputed diff really is different: a whitespace-only change is a change
     * to git and nothing to {@code git diff -w}.
     */
    @Test
    void ignoreWhitespaceEmptiesAWhitespaceOnlyDiff() throws Exception {
        // A file of its own, committed here: committing folds whatever is in the index
        // into HEAD, and Sample.java's staged change is in there. The first version of
        // this test shared the fixture and quietly destroyed the staged case for every
        // test that ran after it.
        write("Spacing.java", "class Spacing {\n    int a = 1;\n}\n");
        assertTrue(service.stageFile("Spacing.java").isOk());
        assertTrue(service.commit("add Spacing", false).isOk());
        write("Spacing.java", "class Spacing {\n\t\tint a = 1;\n}\n");

        byte[] normal = service.getUnstagedDiffBytes("Spacing.java", false);
        byte[] ignoring = service.getUnstagedDiffBytes("Spacing.java", true);

        assertTrue(normal.length > 0, "the indentation really did change");
        // Not zero bytes: the blob ids differ, so git still emits the file header. What
        // -w removes is the hunks, which is what the viewer renders.
        assertTrue(hunkCount(normal) > 0, "the plain diff has a hunk");
        assertEquals(0, hunkCount(ignoring),
                () -> "-w should leave no hunks, got:\n" + new String(ignoring, StandardCharsets.UTF_8));

        // What the viewer does with that: an empty diff for a named file explains itself
        // rather than asking for a selection that has already been made.
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            openedStages.add(stage);
            v.setFileDiffBytes("Spacing.java", ignoring);
            FxHarness.pulse(v);
            return v;
        });
        FxHarness.run(() -> {
            String placeholder = FxHarness.lookupAll(viewer, "diff-empty").stream()
                    .filter(n -> n instanceof javafx.scene.control.Label)
                    .map(n -> ((javafx.scene.control.Label) n).getText())
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst().orElse("");
            assertFalse(placeholder.contains("Select a file"),
                    "a file is selected; the message must not ask for one: " + placeholder);
            // The diff is not empty - it still carries the file header - so the viewer
            // takes the other branch and summarises what git reported, which for a
            // whitespace-only change under -w is that nothing textual moved.
            assertTrue(placeholder.contains("No textual changes"), placeholder);
        });
    }

    // ------------------------------------------------------------------
    // Switching between layouts repeatedly
    // ------------------------------------------------------------------

    /**
     * Flipping back and forth has to keep working.
     *
     * <p>Split builds its columns once and reuses them; unified keeps its own surface.
     * Every render swaps which of the two is in the scene, and getting that wrong leaves
     * either a stale surface on screen or none at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void layoutsCanBeSwappedRepeatedly(Source source) {
        DiffViewer viewer = viewerFor(source);
        FxHarness.run(() -> {
            for (int round = 0; round < 3; round++) {
                toggle(viewer, "Split");
                FxHarness.pulse(viewer);
                assertEquals(2, surfaces(viewer).size(), "round " + round + ": split");
                assertTrue(allText(viewer).contains(source.added()),
                        "round " + round + ": split lost its content");

                toggle(viewer, "Unified");
                FxHarness.pulse(viewer);
                assertEquals(1, surfaces(viewer).size(), "round " + round + ": unified");
                assertTrue(allText(viewer).contains(source.added()),
                        "round " + round + ": unified lost its content");
            }
        });
    }

    /** How many hunks a raw diff carries; the headers around them are not changes. */
    private static long hunkCount(byte[] diff) {
        return new String(diff, StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("@@ "))
                .count();
    }

    /** A short, readable rendering of what was on screen when an assertion failed. */
    private static String describe(String shown) {
        if (shown.isBlank()) return "(the surface was empty)";
        List<String> lines = shown.lines().limit(12).toList();
        return "\n" + String.join("\n", lines) + (shown.lines().count() > 12 ? "\n…" : "");
    }
}
