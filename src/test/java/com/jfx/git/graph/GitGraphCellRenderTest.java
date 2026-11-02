package com.jfx.git.graph;

import com.jfx.git.core.GitBackend;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;

import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lays out real graph rows and measures where the columns landed.
 *
 * <p>The complaint these tests stand for: ref chips used to sit between the lane canvas
 * and the message, so a commit carrying a long branch name started its message far to the
 * right of the one above it and the left edge of the text moved on every row. Refs now
 * have a fixed-width column of their own, and the assertion is simply that the message
 * column begins at the same x on every row regardless of what refs the commit has.
 */
class GitGraphCellRenderTest {

    /**
     * Closes the off-screen stages this class opened.
     *
     * <p>Not housekeeping. A stage left showing goes on taking part in focus and layout
     * for the rest of the run, and the failures that causes land in whichever robot test
     * happens to follow - a click that reaches nothing, a dialog that "never opened".
     */
    @org.junit.jupiter.api.AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    /**
     * A backend that answers everything harmlessly.
     *
     * <p>A proxy rather than a hand-written stub: {@link GitBackend} is a wide interface
     * and the cell only asks it for the current branch while drawing.
     */
    private static GitBackend stubBackend(String currentBranch) {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getCurrentBranch".equals(method.getName())) return currentBranch;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0;
                    if (type == void.class) return null;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    private static CommitInfo commit(String hash, String subject, int minutesAgo,
                                     List<String> parents, List<RefInfo> refs) {
        return new CommitInfo(hash, subject, "ShMO2099", "xmania21@gmail.com",
                Instant.now().minus(minutesAgo, ChronoUnit.MINUTES), parents, refs);
    }

    private static RefInfo local(String name) {
        return new RefInfo("refs/heads/" + name, name, "");
    }

    private static RefInfo remote(String name) {
        return new RefInfo("refs/remotes/" + name, name, "");
    }

    private static RefInfo tag(String name) {
        return new RefInfo("refs/tags/" + name, name, "");
    }

    /**
     * A history shaped like this repository's: a trunk, a side branch that merges back,
     * and refs of very different lengths so the column alignment is actually under test.
     */
    private static List<GitGraphRow> sampleRows() {
        List<CommitInfo> commits = new ArrayList<>();
        commits.add(commit("bbfa9d8aa", "fix: draw the same graph on every engine", 8,
                List.of("ef44246aa"),
                List.of(local("BACKEND_UPDATE"), remote("origin/BACKEND_UPDATE"))));
        commits.add(commit("ef44246aa", "build: package a self-contained Windows application", 55,
                List.of("4c1a9d2aa"), List.of()));
        commits.add(commit("4c1a9d2aa", "Merge branch 'backup/before-reword'", 60,
                List.of("e28d5c1aa", "049270faa"), List.of()));
        commits.add(commit("049270faa", "build: ship the libgit2 licence", 300,
                List.of("a960d3faa"),
                // The long name that used to shove the message column sideways.
                List.of(local("backup/before-reword"))));
        commits.add(commit("7f10b23aa", "temp: enable MRV2 by default for non-pooling models", 2880,
                List.of("e28d5c1aa"),
                // A lone remote with a long name: the case that came out as
                // "origin/njh…" against an otherwise empty rail.
                List.of(remote("origin/njh/attention-free-models"))));
        commits.add(commit("e28d5c1aa", "build: rebuild libgit2 1.9.7 from source", 66,
                List.of("490553eaa"), List.of()));
        commits.add(commit("a960d3faa", "fix: implement the three operations", 360,
                List.of("490553eaa"), List.of()));
        commits.add(commit("490553eaa", "fix: remove an invented jlink parameter", 300,
                List.of("3c66e23aa"), List.of()));
        commits.add(commit("3c66e23aa", "refactor: target Java 25 LTS", 305,
                List.of("832ada1aa"),
                List.of(local("master"), remote("origin/master"))));
        commits.add(commit("832ada1aa", "feat: pluggable git engines", 4320,
                List.of("93896f8aa"),
                List.of(tag("v0.9.0"), remote("origin/release-0.9"))));
        commits.add(commit("93896f8aa", "refactor: replace JGit types at the UI boundary", 4325,
                List.of(), List.of()));

        LaneAssigner.assign(commits);

        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) rows.add(new GitGraphRow(c));
        return rows;
    }

    /** Builds the list, shows it off-screen, and hands back the laid-out root. */
    private static ListView<GitGraphRow> renderedList() {
        return renderedList(com.jfx.git.ui.ThemeManager.Theme.DARK);
    }

    @SuppressWarnings("unchecked") // children are Nodes; index 1 is the graph list
    private static ListView<GitGraphRow> renderedList(com.jfx.git.ui.ThemeManager.Theme theme) {
        return (ListView<GitGraphRow>) rendered(theme).getChildren().get(1);
    }

    /** The header and the list together, laid out and themed as the tab assembles them. */
    private static javafx.scene.layout.VBox rendered(com.jfx.git.ui.ThemeManager.Theme theme) {
        return rendered(theme, GraphNodeStyle.current());
    }

    private static javafx.scene.layout.VBox rendered(com.jfx.git.ui.ThemeManager.Theme theme,
                                                     GraphNodeStyle nodeStyle) {
        return FxHarness.call(() -> {
            GraphDensity density = GraphDensity.current();
            GraphMetrics metrics = new GraphMetrics(density, nodeStyle);
            List<GitGraphRow> rows = sampleRows();
            metrics.update(rows, "BACKEND_UPDATE");

            ListView<GitGraphRow> list = new ListView<>();
            list.getStyleClass().add("commit-graph");
            list.setFixedCellSize(density.rowHeight());
            list.setCellFactory(lv -> new GitGraphCell(OperationRunner.of(stubBackend("BACKEND_UPDATE"), new StatusBar(), () -> { }), (a, b) -> { }, metrics));
            list.getItems().setAll(rows);

            GraphHeader header = new GraphHeader(density, metrics, nodeStyle);
            var root = new javafx.scene.layout.VBox(header, list);
            javafx.scene.layout.VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);
            // The graph sits on a themed panel in the application. Without a ground here
            // the snapshot's default white shows through wherever the rows are
            // transparent, and a dark theme comes out looking like a broken light one.
            root.setStyle("-fx-background-color: -color-bg-default;");

            // Themed the way the application themes a window, rather than by adding the
            // stylesheets by hand: the semantic palette the app is written against
            // (-color-fg-default and the rest) comes from AtlantaFX's user-agent
            // stylesheet, and a scene that only gets the token file resolves none of it
            // and renders one theme's text on the other theme's ground.
            com.jfx.git.ui.ThemeManager.apply(theme);
            Stage stage = FxHarness.show(root, 900, 420);
            com.jfx.git.ui.ThemeManager.register(stage.getScene());
            // After the stage is shown, so the list has a skin and therefore a scrollbar.
            header.alignWith(list);
            FxHarness.pulse(root);
            return root;
        });
    }

    @Test
    @DisplayName("each column title sits over the column it names")
    void headerLinesUpWithTheRows() {
        var root = rendered(com.jfx.git.ui.ThemeManager.Theme.DARK);

        // The message column is the one a reader would notice being out, and it is the
        // one whose left edge the whole layout exists to hold still.
        double titleX = FxHarness.call(() -> {
            for (Node node : FxHarness.lookupAll(root, "graph-header-title")) {
                if (node instanceof javafx.scene.control.Labeled label
                        && "Commit".equals(label.getText())) {
                    return label.localToScene(label.getBoundsInLocal()).getMinX();
                }
            }
            return -1.0;
        });
        double messageX = FxHarness.call(() -> {
            Node message = FxHarness.lookupAll(root, "graph-message").get(0);
            return message.localToScene(message.getBoundsInLocal()).getMinX();
        });

        assertTrue(titleX > 0, "the Commit title was not found");
        assertEquals(messageX, titleX, 1.0,
                "the Commit title is not above the commit messages");
    }

    @Test
    @DisplayName("the graph never overruns the column reserved for it, in either node style")
    void graphStaysInsideItsColumn() {
        // The bug this stands for: the cell read the node style from preferences while the
        // header was handed one, so the header reserved 11px lanes and the cell drew 18px
        // ones - and the graph spilled over the commit message.
        for (GraphNodeStyle style : GraphNodeStyle.values()) {
            var root = rendered(com.jfx.git.ui.ThemeManager.Theme.DARK, style);

            double overrun = FxHarness.call(() -> {
                double worst = 0;
                var messages = FxHarness.lookupAll(root, "graph-message");
                var canvases = FxHarness.lookupAll(root, "lane-canvas");
                for (int i = 0; i < Math.min(messages.size(), canvases.size()); i++) {
                    double canvasRight = canvases.get(i)
                            .localToScene(canvases.get(i).getBoundsInLocal()).getMaxX();
                    double messageLeft = messages.get(i)
                            .localToScene(messages.get(i).getBoundsInLocal()).getMinX();
                    worst = Math.max(worst, canvasRight - messageLeft);
                }
                return worst;
            });

            assertTrue(overrun <= 0.5,
                    style + " draws its lanes " + overrun + "px into the commit message");
        }
    }

    @Test
    @DisplayName("a long commit body never widens the row past the window")
    void longBodyDoesNotWidenTheRow() {
        // The complaint this stands for: age and SHA ended up several seconds of
        // horizontal scrolling off to the right. The message column asked for the full
        // width of its text, the grid's preferred width is the sum of its columns, and the
        // list grew a scrollbar to cover it.
        double[] widths = FxHarness.call(() -> {
            var commits = new ArrayList<CommitInfo>();
            commits.add(new CommitInfo("aaaaaaa",
                    "Optimize HiSparse prefix readiness tracking\n\n"
                            + "Track the contiguous durable host prefix per request and revisit only "
                            + "requests whose spill pages completed. This removes the scheduler-wide "
                            + "rescan and keeps the staged DMA path bounded, and it goes on at some "
                            + "length because real commit bodies do exactly that.",
                    "Matthew Bonanni", "mbonanni@redhat.com",
                    Instant.now().minus(5, ChronoUnit.MINUTES), List.of("bbbbbbb"), List.of()));
            commits.add(commit("bbbbbbb", "short one", 9, List.of(), List.of()));
            LaneAssigner.assign(commits);

            var rows = new ArrayList<GitGraphRow>();
            for (CommitInfo c : commits) rows.add(new GitGraphRow(c));

            GraphDensity density = GraphDensity.current();
            GraphMetrics metrics = new GraphMetrics(density);
            metrics.update(rows, "main");

            ListView<GitGraphRow> list = new ListView<>();
            list.getStyleClass().add("commit-graph");
            list.setFixedCellSize(density.rowHeight());
            list.setCellFactory(lv -> new GitGraphCell(OperationRunner.of(stubBackend("main"), new StatusBar(), () -> { }), (a, b) -> { }, metrics));
            list.getItems().setAll(rows);

            var box = new javafx.scene.layout.VBox(list);
            javafx.scene.layout.VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);
            com.jfx.git.ui.ThemeManager.apply(com.jfx.git.ui.ThemeManager.Theme.DARK);
            Stage stage = FxHarness.show(box, 700, 160);
            com.jfx.git.ui.ThemeManager.register(stage.getScene());
            FxHarness.pulse(box);

            double worst = 0;
            for (Node row : FxHarness.lookupAll(list, "graph-row")) {
                worst = Math.max(worst, row.prefWidth(-1));
            }
            return new double[]{worst, list.getWidth()};
        });

        assertTrue(widths[0] <= widths[1] + 1,
                "a row wants " + Math.round(widths[0]) + "px in a "
                        + Math.round(widths[1]) + "px list, so the SHA column is off-screen");
    }

    @Test
    @DisplayName("the author column exists only when the node is a dot")
    void authorColumnFollowsNodeStyle() {
        for (GraphNodeStyle style : GraphNodeStyle.values()) {
            var root = rendered(com.jfx.git.ui.ThemeManager.Theme.DARK, style);
            boolean titled = FxHarness.call(() ->
                    FxHarness.lookupAll(root, "graph-header-title").stream()
                            .anyMatch(n -> n instanceof javafx.scene.control.Labeled l
                                    && "Author".equals(l.getText())));

            assertEquals(style.needsAuthorColumn(), titled,
                    style + " got the wrong answer about its author column");
        }
    }

    @Test
    @DisplayName("no column title is clipped by its own column")
    void headerTitlesFit() {
        var root = rendered(com.jfx.git.ui.ThemeManager.Theme.DARK);

        List<String> clipped = FxHarness.call(() -> {
            List<String> found = new ArrayList<>();
            for (Node node : FxHarness.lookupAll(root, "graph-header-title")) {
                if (node instanceof javafx.scene.control.Labeled label
                        && label.getWidth() + 0.5 < label.prefWidth(-1)) {
                    found.add(label.getText());
                }
            }
            return found;
        });

        assertTrue(clipped.isEmpty(),
                "these titles are too wide for their columns: " + clipped);
    }

    @Test
    @DisplayName("a long branch name never widens the rail beyond its ceiling")
    void railDoesNotGrowForLongNames() {
        GraphDensity density = GraphDensity.current();
        GraphMetrics metrics = FxHarness.call(() -> {
            GraphMetrics m = new GraphMetrics(density);
            m.update(sampleRows(), "BACKEND_UPDATE");
            return m;
        });

        // The sample contains origin/njh/attention-free-models, far wider than the rail.
        // It is truncated and read by hovering, rather than charging every row in the
        // history the width of a name that appears on one of them.
        assertEquals(density.railWidth(), metrics.railWidth(), 0.5,
                "one long name widened the rail for the whole page");
    }

    @Test
    @DisplayName("short branch names shrink the rail and hand the width to the messages")
    void railShrinksForShortNames() {
        GraphDensity density = GraphDensity.current();

        GraphMetrics metrics = FxHarness.call(() -> {
            var commits = new ArrayList<CommitInfo>();
            commits.add(commit("aaaaaaa", "first", 5, List.of("bbbbbbb"), List.of(local("dev"))));
            commits.add(commit("bbbbbbb", "second", 9, List.of(), List.of()));
            LaneAssigner.assign(commits);

            var rows = new ArrayList<GitGraphRow>();
            for (CommitInfo c : commits) rows.add(new GitGraphRow(c));

            GraphMetrics m = new GraphMetrics(density);
            m.update(rows, "dev");
            return m;
        });

        assertTrue(metrics.railWidth() < density.railWidth(),
                "the rail kept its full width for a two-letter branch name: "
                        + metrics.railWidth());
    }

    @Test
    @DisplayName("every commit message starts at the same x, whatever refs the row carries")
    void messageColumnDoesNotMove() {
        ListView<GitGraphRow> list = renderedList();

        List<Double> starts = FxHarness.call(() -> {
            List<Double> found = new ArrayList<>();
            for (Node label : FxHarness.lookupAll(list, "graph-message")) {
                found.add(label.localToScene(label.getBoundsInLocal()).getMinX());
            }
            return found;
        });

        assertFalse(starts.isEmpty(), "no rows were realised, so nothing was measured");
        double first = starts.get(0);
        for (double start : starts) {
            assertEquals(first, start, 0.5,
                    "the message column moved between rows: " + starts);
        }
    }

    @Test
    @DisplayName("every ref rail is the same width, whatever that page needed")
    void railKeepsItsWidth() {
        ListView<GitGraphRow> list = renderedList();

        List<Double> widths = FxHarness.call(() -> {
            List<Double> found = new ArrayList<>();
            for (Node rail : FxHarness.lookupAll(list, "graph-rail")) {
                found.add(rail.getBoundsInLocal().getWidth());
            }
            return found;
        });

        assertFalse(widths.isEmpty());
        // The width itself is the page's business - what matters is that no row picked
        // its own, because that is what moves the message column between rows.
        double first = widths.get(0);
        for (double width : widths) {
            assertEquals(first, width, 0.5,
                    "a rail took its own width instead of the column's: " + widths);
        }
    }

    @Test
    @DisplayName("a branch and its remote counterpart show as one chip, not two")
    void trackedPairIsOneChip() {
        ListView<GitGraphRow> list = renderedList();

        // Row 0 carries BACKEND_UPDATE and origin/BACKEND_UPDATE.
        long visibleChips = FxHarness.call(() ->
                FxHarness.lookupAll(list, "ref-chip").stream()
                        .filter(Node::isVisible)
                        .filter(chip -> chip instanceof javafx.scene.control.Labeled labeled
                                && labeled.getText() != null
                                && labeled.getText().contains("BACKEND_UPDATE"))
                        .count());

        assertEquals(1, visibleChips,
                "the local branch and its remote counterpart should share a chip");
    }

    @Test
    @DisplayName("the checked-out branch is marked as HEAD")
    void headIsMarked() {
        ListView<GitGraphRow> list = renderedList();

        boolean marked = FxHarness.call(() ->
                FxHarness.lookupAll(list, "ref-chip-head").stream().anyMatch(Node::isVisible));

        assertTrue(marked, "no chip was marked as HEAD");
    }

    @Test
    @DisplayName("the painted lanes follow the theme, not just the styled nodes")
    void lanesArePaintedInTheThemesOwnPalette() {
        // The bug this stands for: lanes are drawn onto a Canvas, which CSS cannot reach
        // and which keeps whatever was last painted on it. Asserting on the palette class
        // alone would not catch a cell that never repainted, so this reads the pixels.
        for (var theme : com.jfx.git.ui.ThemeManager.Theme.values()) {
            ListView<GitGraphRow> list = renderedList(theme);
            var image = FxHarness.call(() -> list.snapshot(null, null));

            // The first row's own colour, whatever index it is: a named tip's colour comes
            // from its branch name, not from being first on the page.
            int firstColour = sampleRows().get(0).getCommit().getColorIndex();
            javafx.scene.paint.Color expected =
                    FxHarness.call(() -> GraphPalette.lane(firstColour));
            javafx.scene.paint.Color ground = FxHarness.call(GraphPalette::ground);
            javafx.scene.paint.Color painted = mostSaturatedInLaneColumn(image, ground);

            assertEquals(expected.getHue(), painted.getHue(), 12.0,
                    theme + " painted its lanes in another theme's palette");
            assertEquals(expected.getBrightness(), painted.getBrightness(), 0.15,
                    theme + " painted its lanes at another theme's brightness");
        }
        FxHarness.run(() -> com.jfx.git.ui.ThemeManager.apply(com.jfx.git.ui.ThemeManager.Theme.DARK));
    }

    /**
     * The most saturated pixel in the first row's lane column, leaving out everything near
     * the row's ground.
     *
     * <p>The commit dot is a solid fill of the lane colour, so it is the most saturated thing
     * in the band - once the backgrounds are left out. "Most saturated" alone is not enough
     * now that themes have grounds of their own: a blue-black ground such as Midnight's is more
     * saturated in HSB than a pale lane is, and a tinted selected row (slate in Nord, teal in
     * Dark Green) is saturated too, so both were picked instead of the dot. Those are all within
     * a few steps of the ground; a lane is not, so a pixel has to be well clear of it to count.
     * Ties go to the first pixel scanned, which is the first row's own dot and not a lane
     * passing through it.
     */
    private static javafx.scene.paint.Color mostSaturatedInLaneColumn(javafx.scene.image.Image image,
                                                                      javafx.scene.paint.Color ground) {
        GraphDensity density = GraphDensity.current();
        int from = density.edgeWidth() + density.railWidth();
        int to = Math.min((int) image.getWidth(), from + 40);
        int rowBottom = Math.min((int) image.getHeight(), density.rowHeight());

        var reader = image.getPixelReader();
        javafx.scene.paint.Color best = javafx.scene.paint.Color.TRANSPARENT;
        for (int y = 0; y < rowBottom; y++) {
            for (int x = from; x < to; x++) {
                var pixel = reader.getColor(x, y);
                double distance = Math.abs(pixel.getRed() - ground.getRed())
                        + Math.abs(pixel.getGreen() - ground.getGreen())
                        + Math.abs(pixel.getBlue() - ground.getBlue());
                if (distance < 0.7) continue;
                if (pixel.getSaturation() > best.getSaturation()) best = pixel;
            }
        }
        return best;
    }

    @Test
    @DisplayName("hovering a clipped ref floats its full name over the row, moving nothing")
    void hoverShowsFullNameWithoutDisturbingLayout() {
        var root = rendered(com.jfx.git.ui.ThemeManager.Theme.DARK);

        Object[] result = FxHarness.call(() -> {
            javafx.scene.control.Label chip = null;
            for (Node node : FxHarness.lookupAll(root, "ref-chip")) {
                if (node instanceof javafx.scene.control.Label label
                        && label.isVisible()
                        && label.getText() != null
                        && label.getText().contains("njh")) {
                    chip = label;
                    break;
                }
            }
            if (chip == null) return new Object[]{null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};

            Node message = FxHarness.lookupAll(root, "graph-message").get(0);
            Node rail = FxHarness.lookupAll(root, "graph-rail").get(0);
            double messageBefore = message.localToScene(0, 0).getX();
            double railBefore = rail.getBoundsInLocal().getWidth();
            double chipWidthBefore = chip.getWidth();

            chip.getOnMouseEntered().handle(mouseEvent(javafx.scene.input.MouseEvent.MOUSE_ENTERED));
            FxHarness.pulse(root);

            javafx.scene.control.Label overlay = null;
            for (Node node : FxHarness.lookupAll(root, "ref-chip-expanded")) {
                if (node instanceof javafx.scene.control.Label label) overlay = label;
            }

            return new Object[]{
                    overlay == null ? null : overlay.getText(),
                    messageBefore, message.localToScene(0, 0).getX(),
                    railBefore - rail.getBoundsInLocal().getWidth(),
                    chipWidthBefore - chip.getWidth(),
                    overlay == null ? 0.0 : overlay.getWidth(),
                    overlay == null ? 0.0 : overlay.prefWidth(-1)};
        });

        assertEquals("origin/njh/attention-free-models", result[0],
                "the floating label did not show the full ref name");

        // The point of an unmanaged overlay: the layout never sees it.
        assertEquals((double) result[1], (double) result[2], 0.5,
                "showing a ref name moved the commit message");
        assertEquals(0.0, (double) result[3], 0.5, "showing a ref name resized the rail");
        assertEquals(0.0, (double) result[4], 0.5, "showing a ref name resized the chip itself");

        // The overlay sizes itself, and it used to do so before CSS had applied the classes
        // that give it its font and padding - measuring an unstyled label, coming back
        // short, and ellipsising the very name it exists to show. That is how hovering a
        // chip produced "● …". It only bites a name long enough to be clipped, which is
        // the chip this test hovers.
        assertTrue((double) result[5] + 0.5 >= (double) result[6],
                "the overlay was sized to " + result[5] + "px but needs " + result[6]
                        + "px, so it is ellipsising the name it exists to show");
    }

    /**
     * A tag has to be recognisable as a tag without reading its colour.
     *
     * <p>It used to say so in a tooltip - "tag: v1.0" - and that went when named chips
     * stopped carrying tooltips, leaving the green fill as the only signal. So the chip is
     * shaped as well as coloured, the same two-signal rule HEAD already follows.
     *
     * <p>Its own two-row history rather than {@link #sampleRows}, where the tagged commit
     * also carries a remote branch and the rail is only wide enough for one chip, so the
     * tag sits behind a {@code +1} and is never drawn.
     */
    @Test
    @DisplayName("a tag is a different shape from a branch, not only a different colour")
    void tagChipIsShapedUnlikeABranch() {
        var root = FxHarness.call(() -> {
            var commits = new ArrayList<CommitInfo>();
            commits.add(commit("aaaaaaa", "tagged", 5, List.of("bbbbbbb"), List.of(tag("v1.0"))));
            commits.add(commit("bbbbbbb", "branched", 9, List.of(), List.of(local("dev"))));
            LaneAssigner.assign(commits);

            var rows = new ArrayList<GitGraphRow>();
            for (CommitInfo c : commits) rows.add(new GitGraphRow(c));

            GraphDensity density = GraphDensity.current();
            GraphMetrics metrics = new GraphMetrics(density);
            metrics.update(rows, "dev");

            ListView<GitGraphRow> list = new ListView<>();
            list.getStyleClass().add("commit-graph");
            list.setFixedCellSize(density.rowHeight());
            list.setCellFactory(lv -> new GitGraphCell(OperationRunner.of(stubBackend("dev"), new StatusBar(), () -> { }), (a, b) -> { }, metrics));
            list.getItems().setAll(rows);

            var box = new javafx.scene.layout.VBox(new GraphHeader(density, metrics), list);
            javafx.scene.layout.VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);
            com.jfx.git.ui.ThemeManager.apply(com.jfx.git.ui.ThemeManager.Theme.DARK);
            Stage stage = FxHarness.show(box, 900, 200);
            com.jfx.git.ui.ThemeManager.register(stage.getScene());
            FxHarness.pulse(box);
            return box;
        });

        Object[] radii = FxHarness.call(() -> new Object[]{
                chipRadii(root, "ref-chip-tag"), chipRadii(root, "ref-chip-local"),
                chipsSeen(root)});

        var tag = (javafx.scene.layout.CornerRadii) radii[0];
        var branch = (javafx.scene.layout.CornerRadii) radii[1];
        // Named rather than skipped: both chips are in this history by construction, so
        // one going missing is a rendering change and worth failing over.
        assertNotNull(tag, "no visible tag chip; saw " + radii[2]);
        assertNotNull(branch, "no visible local branch chip; saw " + radii[2]);

        assertTrue(tag.getTopRightHorizontalRadius() > tag.getTopLeftHorizontalRadius() + 1,
                "the tag chip is evenly rounded like a branch, so green is the only thing "
                        + "telling the two apart: radii were " + tag);
        assertEquals(branch.getTopLeftHorizontalRadius(), branch.getTopRightHorizontalRadius(),
                0.5, "a branch chip is meant to be evenly rounded: " + branch);
    }

    /** Every visible ref chip, for a failure message that says what was actually drawn. */
    private static String chipsSeen(Node root) {
        StringBuilder seen = new StringBuilder();
        for (Node node : FxHarness.lookupAll(root, "ref-chip")) {
            if (node instanceof javafx.scene.control.Label label && label.isVisible()) {
                if (!seen.isEmpty()) seen.append(", ");
                seen.append(label.getText()).append(' ').append(label.getStyleClass());
            }
        }
        return seen.toString();
    }

    /** The corner radii the theme gives the first visible chip in {@code styleClass}. */
    private static javafx.scene.layout.CornerRadii chipRadii(Node root, String styleClass) {
        for (Node node : FxHarness.lookupAll(root, styleClass)) {
            if (node instanceof javafx.scene.control.Label label
                    && label.isVisible()
                    && label.getBackground() != null
                    && !label.getBackground().getFills().isEmpty()) {
                return label.getBackground().getFills().get(0).getRadii();
            }
        }
        return null;
    }

    private static javafx.scene.input.MouseEvent mouseEvent(
            javafx.event.EventType<javafx.scene.input.MouseEvent> type) {
        return new javafx.scene.input.MouseEvent(type, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.NONE, 0,
                false, false, false, false, false, false, false, false, false, false, null);
    }
}
