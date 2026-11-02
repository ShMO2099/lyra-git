package com.jfx.git.ui;

import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Switching the file a diff viewer is showing must not throw.
 *
 * <h2>The crash this reproduces</h2>
 * Selecting an unstaged file produced
 * {@code NoSuchElementException: No value present} from
 * {@code Optional.get} inside Flowless's {@code CellListManager.getPresentCell}, reached
 * through {@code DiffViewer.layoutHunkActions → hunkTopIfVisible →
 * getCharacterBoundsOnScreen}. RichTextFX answers that question by forcing a layout, and
 * a layout run while the cell list is still catching up with replaced text asks for a
 * cell that is not there yet.
 *
 * <p>{@code hunkTopIfVisible} already handles the <em>empty</em> Optional that call can
 * return. It cannot handle the exception, because the throw happens inside the call
 * rather than at it — which is why the existing guard looked sufficient and was not.
 *
 * <h2>Why the existing tests missed it</h2>
 * {@code layoutHunkActions} returns immediately unless a stage or revert handler is
 * installed, and five of the seven tests that render a diff never install one — including
 * all fifty-four combinations of {@code DiffViewerMatrixTest}. The path that throws was
 * simply never entered. The two tests that do install a handler set their content once,
 * on a fresh area; the crash needs content <em>replaced</em> on an area that is already
 * laid out, which is what selecting a second file does.
 */
class DiffViewerHunkBarCrashTest {

    /**
     * Stages opened here, closed again afterwards.
     *
     * <p>Each holds a live RichTextFX area with hunk-action bars over it. Left showing,
     * they go on costing layout work for the rest of the run, which is charged to
     * whichever robot test happens to follow.
     */
    private final List<Stage> openedStages = new ArrayList<>();

    @AfterEach
    void closeStages() {
        FxHarness.run(() -> {
            openedStages.forEach(Stage::hide);
            openedStages.clear();
        });
    }

    private static final String DIFF_A = """
            @@ -1,4 +1,5 @@
             package com.jfx.git.ui.dialogs;
            -import javafx.scene.control.Label;
            +import javafx.scene.control.TextField;
            +import javafx.scene.layout.GridPane;
             public class NewTagDialog {
            """;

    /** Longer than A, so the cell list has to grow rather than only change. */
    private static final String DIFF_B = """
            @@ -1,3 +1,9 @@
             package com.jfx.git.ui;
            +import javafx.scene.control.Button;
            +import javafx.scene.control.Label;
            +import javafx.scene.layout.HBox;
            +import javafx.scene.layout.Priority;
            +import javafx.scene.layout.Region;
            +import javafx.stage.Stage;
             public class StatusBar {
            @@ -40,6 +46,8 @@
                 private void show() {
            +        // another hunk, further down
            +        layout();
                 }
            """;

    /**
     * A viewer with a hunk handler installed, laid out in a real viewport.
     *
     * <p>Both halves matter. Without the handler {@code layoutHunkActions} returns before
     * it can throw; without a laid-out viewport Flowless never materialises a cell to go
     * looking for.
     */
    private DiffViewer showViewer() {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            // This is what arms the path: no runner, or a diff with no actions, means no
            // hunk bars, no crash, no coverage.
            viewer.setOnHunkAction(action -> { });

            Stage stage = FxHarness.show(new VBox(viewer), 1000, 700);
            ThemeManager.register(stage.getScene());
            openedStages.add(stage);
            viewer.setFileDiff(DiffKind.UNSTAGED, "src/main/java/com/jfx/git/ui/dialogs/NewTagDialog.java", DIFF_A);
            FxHarness.pulse(viewer);
            return viewer;
        });
    }

    /** Selecting a second file replaces the content of an already-laid-out area. */
    @Test
    void switchingFilesDoesNotThrow() {
        DiffViewer viewer = showViewer();
        assertDoesNotThrow(() -> FxHarness.run(() -> {
            viewer.setFileDiff(DiffKind.UNSTAGED, "src/main/java/com/jfx/git/ui/StatusBar.java", DIFF_B);
            FxHarness.pulse(viewer);
        }), "switching the displayed file must not throw out of the layout pass");
    }

    /** The same thing repeatedly, which is what clicking down a file list is. */
    @Test
    void walkingDownAFileListDoesNotThrow() {
        DiffViewer viewer = showViewer();
        assertDoesNotThrow(() -> FxHarness.run(() -> {
            for (int i = 0; i < 6; i++) {
                viewer.setFileDiff(DiffKind.UNSTAGED, "file" + i + ".java", i % 2 == 0 ? DIFF_B : DIFF_A);
                FxHarness.pulse(viewer);
            }
        }), "walking a file list must not throw");
    }

    /**
     * An empty diff after a populated one: the file list still lists a path whose diff
     * has since become empty, which is the exact case that reached the user.
     */
    @Test
    void replacingAPopulatedDiffWithAnEmptyOneDoesNotThrow() {
        DiffViewer viewer = showViewer();
        assertDoesNotThrow(() -> FxHarness.run(() -> {
            viewer.setFileDiff(DiffKind.UNSTAGED, "src/main/java/com/jfx/git/core/GitService.java", "");
            FxHarness.pulse(viewer);
        }), "an empty diff must not throw");
    }

    /** Selection changes are what the listener at the heart of this actually listens to. */
    @Test
    void movingTheSelectionDoesNotThrow() {
        DiffViewer viewer = showViewer();
        assertDoesNotThrow(() -> FxHarness.run(() -> {
            viewer.setFileDiff(DiffKind.UNSTAGED, "b.java", DIFF_B);
            FxHarness.pulse(viewer);
            DiffTextArea area = (DiffTextArea) FxHarness.lookupAll(viewer, "code-area").get(0);
            area.selectRange(0, 5);
            FxHarness.pulse(viewer);
            area.selectRange(0, 0);
            FxHarness.pulse(viewer);
        }), "moving the selection must not throw");
    }
}
