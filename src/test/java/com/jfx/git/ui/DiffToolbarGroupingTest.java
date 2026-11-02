package com.jfx.git.ui;

import javafx.scene.Node;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The diff toolbar's controls are grouped by what they act on, and cheaply.
 *
 * <h2>Why a separator and not a label</h2>
 * The design called for three labelled groups — Show, Layout, Draw. This toolbar is set
 * to {@code minWidth 0} on purpose, so that its natural width does not become the
 * enclosing {@code SplitPane}'s minimum; the consequence is that it <em>clips</em> when
 * the pane is narrow rather than forcing the pane wider. Every pixel of width therefore
 * costs a control that vanishes sooner in a narrow pane.
 *
 * <p>Three text labels cost roughly ninety pixels. One separator costs seven, and buys
 * the same thing the labels were for: the layout controls and the drawing controls stop
 * reading as one strip. That trade is what this class pins — the grouping has to be
 * present, and the toolbar has to stay narrow enough to be worth having.
 */
class DiffToolbarGroupingTest {

    /**
     * Stages opened here, closed again afterwards.
     *
     * <p>Not optional. Leaving them showing is what made CherryPickRobotTest fail from
     * three classes away earlier in this work: enough live RichTextFX areas accumulate
     * that the fixed sleeps in the robot tests running afterwards stop being long enough.
     */
    private final List<Stage> openedStages = new ArrayList<>();

    @AfterEach
    void closeStages() {
        FxHarness.run(() -> {
            openedStages.forEach(Stage::hide);
            openedStages.clear();
        });
    }

    private static final String DIFF = """
            @@ -1,3 +1,4 @@
             context
            -removed
            +added
            +also added
            """;

    private DiffViewer viewer() {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(new VBox(viewer), 1100, 600);
            ThemeManager.register(stage.getScene());
            viewer.setFileDiff("src/Main.java", DIFF);
            FxHarness.pulse(viewer);
            openedStages.add(stage);
            return viewer;
        });
    }

    /** The row holding the diff-only controls, found by the separators it contains. */
    private static HBox diffTools(DiffViewer viewer) {
        List<Node> rows = FxHarness.lookupAll(viewer, "diff-toolbar");
        assertFalse(rows.isEmpty(), "the viewer should have a toolbar");
        HBox toolbar = (HBox) rows.get(0);
        for (Node child : toolbar.getChildren()) {
            if (child instanceof HBox box
                    && box.getChildren().stream().anyMatch(n -> n instanceof Separator)) {
                return box;
            }
        }
        throw new AssertionError("no diff-only group found in the toolbar");
    }

    /**
     * Three groups means three separators: before hunk navigation, before layout, and
     * before the drawing toggles.
     *
     * <p>The third was missing — Whole file sat directly against the whitespace toggles,
     * so everything after the second separator read as one strip of nine controls.
     */
    @Test
    void theDiffControlsAreSeparatedIntoThreeGroups() {
        DiffViewer viewer = viewer();
        FxHarness.run(() -> {
            long separators = diffTools(viewer).getChildren().stream()
                    .filter(n -> n instanceof Separator)
                    .count();
            assertEquals(3, separators,
                    "navigation, layout and drawing each need their own boundary");
        });
    }

    /**
     * The grouping must not have been bought with width.
     *
     * <h2>What the number turned out to be</h2>
     * 653px, measured — not the couple of hundred assumed when the separator was chosen
     * over labels. The relative argument still holds (a separator is seven pixels and
     * three labels are ninety), but the absolute figure says something more useful: this
     * group is already wide enough that a diff pane narrower than roughly nine hundred
     * pixels is clipping controls off its right-hand end today, before anything is added.
     *
     * <p>That is worth knowing rather than smoothing over. It means the case for text
     * labels is weaker than the design sketch assumed — there is no spare room to spend —
     * and it means the eventual fix for a narrow pane is to let these controls collapse
     * into an overflow menu, not to keep shaving pixels off them.
     *
     * <p>The bound is the measurement plus headroom. It exists so the next addition is a
     * decision rather than an accident.
     */
    @Test
    void theToolbarStaysNarrowEnoughToClipLate() {
        DiffViewer viewer = viewer();
        FxHarness.run(() -> {
            HBox tools = diffTools(viewer);
            double natural = tools.prefWidth(-1);
            System.out.println("Diff tool group natural width: " + natural + "px");
            // 653px when first set; 786px once the "Change 2 of 5" counter and the
            // Context 3/10/All selector replaced Whole file - both asked for by the UI
            // review, and both paid for knowingly, since below this width the controls
            // now fold into the View options menu rather than clipping.
            assertTrue(natural < 820,
                    "the diff-only controls were " + natural + "px. They were 786px when"
                            + " this bound was last set; something has been added, which"
                            + " is what this exists to make deliberate.");
        });
    }

    /**
     * The document facts left the toolbar for the footer.
     *
     * <p>Not cosmetic. {@code updateToolbarOverflow} sums {@code prefWidth} across this
     * row's managed children to decide when the diff controls collapse into the overflow
     * menu, so two labels moving out is width the controls get back - which is what the
     * bound above exists to protect.
     */
    @Test
    void theToolbarNoLongerCarriesTheDocumentFacts() {
        DiffViewer viewer = viewer();
        FxHarness.run(() -> {
            HBox toolbar = (HBox) FxHarness.lookupAll(viewer, "diff-toolbar").get(0);
            assertTrue(FxHarness.lookupAll(toolbar, "diff-notice").isEmpty(),
                    "the syntax notice is a fact about the file, not a toolbar action");
            assertFalse(FxHarness.lookupAll(viewer, "diff-status-footer").isEmpty(),
                    "and it should have landed in the footer");
        });
    }

    /** Whatever the grouping, the toolbar must never set the pane's minimum width. */
    @Test
    void theToolbarStillDoesNotForceThePaneWider() {
        DiffViewer viewer = viewer();
        FxHarness.run(() -> {
            HBox toolbar = (HBox) FxHarness.lookupAll(viewer, "diff-toolbar").get(0);
            assertEquals(0, toolbar.getMinWidth(),
                    "a toolbar that reports its natural width as a minimum makes the"
                            + " enclosing SplitPane overflow instead of honouring dividers");
        });
    }
}
