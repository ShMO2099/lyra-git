package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A merge that really conflicts, resolved through the real Merge Tool, ending in a real
 * merge commit.
 *
 * <h2>What makes this a real conflict</h2>
 * Two branches each rewrite the same line of the same file, from a shared ancestor. That
 * is the one shape of change JGit cannot auto-resolve, so {@link GitService#merge} is
 * guaranteed to return a conflict rather than silently succeeding — the setup a stub could
 * not stand in for.
 *
 * <h2>Reaching the branch tree's context menu under Monocle Headless</h2>
 * As with every other context menu this session, a robot right-click does not become a
 * {@code ContextMenuEvent} under Monocle Headless. The event is fired directly on the
 * branch's tree cell, which is what {@code TreeCell.setContextMenu} itself listens for.
 */
class MergeConflictRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());

        // A shared base, then the same line rewritten two different ways on two branches.
        commit("shared.txt", "base\n", "base commit");
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("shared.txt", "feature line\n", "feature: rewrite the line");
        assertTrue(service.checkoutBranch("main").isOk()
                || service.checkoutBranch("master").isOk(),
                "neither default branch name checked out");
        commit("shared.txt", "main line\n", "main: rewrite the line too");

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        // 1200x700, not larger: Monocle Headless's virtual screen has a fixed pixel
        // buffer, and a bigger request overflows it with a BufferOverflowException
        // inside Framebuffer.composePixels rather than clipping - every other robot
        // test in this session stays at or under this size for the same reason.
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
        settle();
    }

    @AfterEach
    void tidyUp() {
        // Merging, resolving and committing all run on the git lane; they must be finished
        // before the backend closes and @TempDir deletes the repository under them.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(javafx.stage.Window.getWindows())
                .forEach(javafx.stage.Window::hide));
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Fires a real context-menu request on {@code node} and clicks {@code item} in it.
     * Returns once the click is made; whatever the item starts is the caller's to wait for.
     */
    private void chooseFromMenu(Node node, String item) {
        interact(() -> Event.fireEvent(node, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
        Node menuItem = from(lookup(".context-menu")).lookup(item).query();
        clickOn(menuItem);
    }

    /** Merges "feature" into the current branch, leaving a real conflict, and opens it. */
    private void mergeFeatureAndOpenStaging() {
        // The sidebar is filled by the refresh started as the window opened, off the FX thread.
        RobotWait.untilOnFx("the feature branch never appeared",
                () -> lookup("feature").tryQuery().isPresent());
        Node featureRow = lookup("feature").query();
        // The item's exact label depends on the current branch's name (main or master,
        // whichever this JGit version defaults to), so it is read rather than assumed.
        chooseFromMenu(featureRow, "Merge into " + service.getCurrentBranch());
        // The merge runs on the git lane, and the WIP row arrives with the reload after it.
        // The tree was clean before, so a WIP row at the top means that reload has landed.
        RobotWait.untilOnFx("the merge never produced a WIP row",
                () -> tab.graphList().getItems().get(0).isWipNode());
        selectWipNode();
    }

    /**
     * Selects the WIP row to open the staging area, by index rather than by clicking its
     * displayed text.
     *
     * <p>{@code ListView} only realises rows currently scrolled into view, and a graph
     * reload - which resolving a conflict triggers, along with everything else that
     * refreshes the tab - can leave the WIP row off-screen or simply not yet
     * re-realised. A text lookup for "Uncommitted changes" then finds nothing, which is
     * a fact about virtualization rather than about whether the row is really there.
     * Selecting it through the list's own model is what a click ultimately does anyway,
     * and does not depend on what has been laid out.
     */
    private void selectWipNode() {
        // Cleared first rather than selected directly: if the model already reports
        // index 0 as selected from before a refresh, re-selecting the same index is a
        // no-op that never fires the change listener showStagingArea() is wired to,
        // even though the row object behind that index was just replaced by the reload.
        // A real null -> row transition fires it unconditionally.
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        // The panel is built from a status query off the FX thread. Matched against real
        // git state, so the panel from before a resolve cannot pass for the one after it.
        // Conflicts are listed in their own section, apart from the other unstaged files.
        var expected = service.getStagingState();
        var conflicted = expected.unstaged().stream().filter(expected.conflicting()::contains).toList();
        var plain = expected.unstaged().stream().filter(p -> !expected.conflicting().contains(p)).toList();
        RobotWait.untilOnFx("the staging area never showed " + expected,
                () -> stagingRows("staging-conflicted-list").equals(conflicted)
                        && stagingRows("staging-unstaged-list").equals(plain)
                        && stagingRows("staging-staged-list").equals(expected.staged()));
        // Built in a callback; laid out on the pulse after it, before anything clicks into it.
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The rows of one staging list, copied. Call on the FX thread. */
    private java.util.List<?> stagingRows(String styleClass) {
        return FxHarness.lookupAll(tab.getContent(), styleClass).stream()
                .filter(n -> n instanceof javafx.scene.control.ListView<?>)
                .<java.util.List<?>>map(n -> java.util.List.copyOf(
                        ((javafx.scene.control.ListView<?>) n).getItems()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ." + styleClass + " yet"));
    }

    /**
     * Clicks the conflicted file's row in the Conflicted files section, which opens the merge
     * editor, and returns the editor. Its buttons are looked up inside it: the row's own
     * Mark resolved, beside the selected row, carries the same words.
     */
    private DialogPane openMergeToolFor(String filename) {
        RobotWait.untilOnFx("no conflicted row showing " + filename + " appeared",
                () -> from(lookup(".staging-conflicted-list")).lookup(filename).tryQuery().isPresent());
        Node fileRow = from(lookup(".staging-conflicted-list")).lookup(filename).query();
        clickOn(fileRow);
        return RobotWait.untilDialogWithButton("Mark resolved");
    }

    /** Replaces the whole result with one side, from the toolbar's Take menu. */
    private void takeWholeFile(String item) {
        clickOn("Take");
        clickOn(from(lookup(".context-menu")).lookup(item).queryAs(Node.class));
        settle();
    }

    private RevCommit head() throws Exception {
        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD"));
        }
    }

    /** Whether HEAD is a two-parent commit; a repository mid-commit reads as "not yet". */
    private boolean headIsAMerge() {
        try {
            return head().getParentCount() == 2;
        } catch (Exception midCommit) {
            return false;
        }
    }

    /**
     * The commit/amend button, found by style class since its text is dynamic
     * ("Commit 1 file to main") rather than a fixed word. No dialog sharing this style
     * class is ever open at the same time as the tab it is searched within.
     */
    private Button commitButton() {
        for (Node n : FxHarness.lookupAll(tab.getContent(), "btn-primary")) {
            if (n instanceof Button b) {
                return b;
            }
        }
        throw new AssertionError("no commit button found");
    }

    // ------------------------------------------------------------------

    @Test
    void mergingADivergedBranchProducesARealConflict() {
        mergeFeatureAndOpenStaging();

        assertFalse(service.getStagingState().conflicting().isEmpty(),
                "the merge should have left a real conflict for git, not just a UI banner");
        assertNotNull(lookup(".conflict-banner").tryQuery().orElse(null),
                "the banner should be visible once the WIP node is open");
    }

    @Test
    void resolvingWithTakeOursAndSavingProducesARealMergeCommit() throws Exception {
        mergeFeatureAndOpenStaging();
        DialogPane tool = openMergeToolFor("shared.txt");

        takeWholeFile("Take ours");
        clickOn(from(tool).lookup("Mark resolved").queryAs(Node.class));
        RobotWait.until("the conflict was never resolved",
                () -> service.getStagingState().conflicting().isEmpty());

        assertTrue(service.getStagingState().conflicting().isEmpty(),
                "resolving should have cleared the conflict for real");
        assertEquals("main line\n", Files.readString(tempDir.resolve("shared.txt")),
                "Take ours should have written main's content, with no conflict markers");

        // Resolving refreshes the whole tab, including the graph, which is not
        // guaranteed to leave the WIP row selected - clicked again explicitly rather
        // than assumed, the same way opening the merge tool selected the file's row
        // explicitly instead of relying on hover.
        // Once the resolve's own callback has run and queued that refresh, so the reload
        // cannot land after the reselect and rebuild the panel under the typing below.
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        selectWipNode();

        // Complete the merge with a real commit. Its content diff against HEAD is empty -
        // "Take ours" reproduced HEAD's bytes exactly - so the button must be enabled by
        // the merge still being in progress, not by anything staged.
        // selectWipNode() matched the staging lists against git, but after "Take ours" both
        // lists are empty before the rebuild and after it, so the panel from before the
        // resolve passes that check too. Typing into it lost the message when the rebuilt
        // panel replaced it, and the commit click then had nothing to commit with. The
        // rebuilt panel is the one offering to finish the merge.
        RobotWait.untilOnFx("the rebuilt composer never offered to finish the merge",
                () -> !commitButton().isDisabled()
                        && commitButton().getText().startsWith("Commit merge"));
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        var message = (javafx.scene.control.TextInputControl) lookup(".composer-message").query();
        clickOn(message);
        interact(message::clear);
        write("merge feature");
        RobotWait.untilOnFx("the message never reached the composer",
                () -> message.getScene() != null && "merge feature".equals(message.getText()));
        // Recorded before pressing: a full-suite run once left the status bar empty and HEAD
        // unmoved - the handler never ran, so the robot's click had landed on something
        // other than the button. The composer's neighbours (Sign off, Add co-author) have
        // tooltips, and a tooltip is a popup window under the pointer.
        String popupsBefore = FxHarness.call(() -> javafx.stage.Window.getWindows().stream()
                .filter(javafx.stage.Window::isShowing)
                .map(w -> w.getClass().getSimpleName()).toList().toString());
        // Fired rather than clicked for the same reason: what this test proves is that the
        // commit completes the merge, not that the pointer can reach the button.
        interact(() -> commitButton().fire());
        try {
            RobotWait.until("the merge commit never landed", this::headIsAMerge);
        } catch (AssertionError notLanded) {
            // Only ever seen late in a full suite run, never alone: say what the app said.
            throw new AssertionError(notLanded.getMessage() + " - status bar: \""
                    + FxHarness.call(() -> lookup(".status-message").tryQuery()
                            .map(n -> ((javafx.scene.control.Label) n).getText()).orElse("<none>"))
                    + "\", HEAD: " + head().getShortMessage()
                    + ", conflicting: " + service.getStagingState().conflicting()
                    + ", windows before the press: " + popupsBefore, notLanded);
        }

        RevCommit merge = head();
        assertEquals(2, merge.getParentCount(),
                "completing a resolved merge should produce a real two-parent commit");
        assertEquals("main line\n", Files.readString(tempDir.resolve("shared.txt")));
    }

    @Test
    void resolvingWithTakeTheirsKeepsTheIncomingContent() throws Exception {
        mergeFeatureAndOpenStaging();
        DialogPane tool = openMergeToolFor("shared.txt");

        takeWholeFile("Take theirs");
        clickOn(from(tool).lookup("Mark resolved").queryAs(Node.class));
        RobotWait.until("the resolution was never written", () -> {
            try {
                return "feature line\n".equals(Files.readString(tempDir.resolve("shared.txt")));
            } catch (Exception midWrite) {
                return false;
            }
        });

        assertEquals("feature line\n", Files.readString(tempDir.resolve("shared.txt")),
                "Take theirs should have written feature's content");
    }

    @Test
    void cancellingTheMergeToolLeavesTheConflictInPlace() throws Exception {
        mergeFeatureAndOpenStaging();
        openMergeToolFor("shared.txt");

        takeWholeFile("Take ours");
        clickOn("Cancel");
        // Nothing should run, so there is nothing to wait for; let anything that wrongly
        // started finish before checking that the conflict is still there.
        RobotWait.drainGitWork();

        assertFalse(service.getStagingState().conflicting().isEmpty(),
                "cancelling must not resolve the conflict, even though the editor briefly "
                        + "showed a candidate resolution");
        String onDisk = Files.readString(tempDir.resolve("shared.txt"));
        assertTrue(onDisk.contains("<<<<<<<") || onDisk.contains("======="),
                "the working tree should still carry real conflict markers");
    }

    /**
     * Save draft writes the result and leaves the file conflicted; resolving is a separate
     * step, and only it stages.
     */
    @Test
    void savingADraftWritesTheFileButKeepsItConflicted() throws Exception {
        mergeFeatureAndOpenStaging();
        openMergeToolFor("shared.txt");

        clickOn("Use theirs");
        settle();
        clickOn("Save draft");
        RobotWait.until("the draft was never written", () -> {
            try {
                return "feature line\n".equals(Files.readString(tempDir.resolve("shared.txt")));
            } catch (Exception midWrite) {
                return false;
            }
        });
        RobotWait.drainGitWork();

        assertTrue(service.getStagingState().conflicting().contains("shared.txt"),
                "a draft is not a resolution: the file must still be conflicted");
    }
}
