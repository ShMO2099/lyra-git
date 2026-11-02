package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A merge stopped on two conflicting files, worked through the conflict mode the way
 * commercial clients lay it out: the headline naming both branches, the conflicts in a section of
 * their own, a click opening the merge editor, the next file offered after each one, the
 * resolved ones tagged among the staged, and the strip over the graph for as long as the
 * merge is stopped.
 *
 * <p>A real repository and a real conflict: both branches rewrite the same line of both
 * files from a shared ancestor, which no engine can merge by itself.
 */
class ConflictFlowRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    /** main or master, whichever this JGit version starts a repository on. */
    private String mainBranch;

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

        commit("base", "a.txt", "base a\n", "b.txt", "base b\n");
        mainBranch = service.getCurrentBranch();
        assertTrue(service.createBranch("feature/simple", null, true).isOk());
        commit("feature: rewrite both", "a.txt", "feature a\n", "b.txt", "feature b\n");
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        commit("main: rewrite both too", "a.txt", "main a\n", "b.txt", "main b\n");
        // Stopped before the tab opens, so every test starts from the same stopped merge.
        assertTrue(new RepositoryCommands(service).merge("feature/simple", false).isConflict());

        tab = new RepoTab(new LyraApp(), "test", service);
        // 1200x700: Monocle Headless's fixed frame buffer; see MergeConflictRobotTest.
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void commit(String message, String... pathsAndContents) throws Exception {
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            Files.writeString(tempDir.resolve(pathsAndContents[i]), pathsAndContents[i + 1], StandardCharsets.UTF_8);
            assertTrue(service.stageFile(pathsAndContents[i]).isOk());
        }
        assertTrue(service.commit(message, false).isOk());
    }

    // ------------------------------------------------------------------
    // What is on screen
    // ------------------------------------------------------------------

    /** Opens the uncommitted changes and waits for both conflicts to be listed. */
    private void openWorkingTree() {
        RobotWait.untilOnFx("the stopped merge never produced a WIP row",
                () -> !tab.graphList().getItems().isEmpty() && tab.graphList().getItems().get(0).isWipNode());
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("the Conflicted files section never listed both files",
                () -> rows("staging-conflicted-list").equals(List.of("a.txt", "b.txt")));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The items of the list carrying {@code styleClass}, or none while there is no such list. Call on FX. */
    private List<String> rows(String styleClass) {
        return FxHarness.lookupAll(tab.getContent(), styleClass).stream()
                .filter(node -> node instanceof ListView<?>)
                .<List<String>>map(node -> ((ListView<?>) node).getItems().stream().map(String::valueOf).toList())
                .findFirst().orElse(List.of());
    }

    private Node operationBanner() {
        return FxHarness.lookupAll(tab.getContent(), "operation-banner").stream().findFirst().orElse(null);
    }

    /** The banner's headline as one line, or "" while there is none. Call on FX. */
    private String headline() {
        Node banner = operationBanner();
        return banner == null ? "" : FxHarness.lookupAll(banner, "operation-headline").stream()
                .map(Node::getAccessibleText).findFirst().orElse("");
    }

    /** The ref chips in the banner's headline, in order. Call on FX. */
    private List<String> headlineChips() {
        Node banner = operationBanner();
        return banner == null ? List.of() : FxHarness.lookupAll(banner, "ref-chip").stream()
                .map(node -> ((Label) node).getText()).toList();
    }

    private Button bannerButton(String text) {
        Node banner = operationBanner();
        return banner == null ? null : FxHarness.lookupAll(banner, "button").stream()
                .filter(node -> node instanceof Button button && text.equals(button.getText()))
                .map(node -> (Button) node).findFirst().orElse(null);
    }

    private boolean stripShowing() {
        return tab.operationStrip().isVisible() && tab.operationStrip().isManaged();
    }

    /** The button on {@code path}'s row of the Conflicted files list labelled {@code text}. Call on FX. */
    private Button conflictRowButton(String path, String text) {
        for (Node node : FxHarness.lookupAll(tab.getContent(), "staging-conflicted-list")) {
            for (Node cellNode : node.lookupAll(".list-cell")) {
                if (cellNode instanceof ListCell<?> cell && path.equals(cell.getItem())) {
                    return FxHarness.lookupAll(cell, "button").stream()
                            .filter(child -> child instanceof Button button && text.equals(button.getText()))
                            .map(child -> (Button) child).findFirst().orElse(null);
                }
            }
        }
        return null;
    }

    /** Whether {@code path}'s row in the Staged list carries the Resolved tag. Call on FX. */
    private boolean taggedResolved(String path) {
        for (Node node : FxHarness.lookupAll(tab.getContent(), "staging-staged-list")) {
            for (Node cellNode : node.lookupAll(".list-cell")) {
                if (cellNode instanceof ListCell<?> cell && path.equals(cell.getItem())) {
                    return FxHarness.lookupAll(cell, "resolved-tag").stream().anyMatch(Node::isVisible);
                }
            }
        }
        return false;
    }

    /** The "Resolve next" action on a notification, or null. Call on FX. */
    private Button nextOffer() {
        return FxHarness.lookupAll(tab.getContent().getScene().getRoot(), "toast-action").stream()
                .filter(node -> node instanceof Button button && button.getText().startsWith("Resolve next: "))
                .map(node -> (Button) node).findFirst().orElse(null);
    }

    /** Whether a merge editor is open. Call on FX. */
    private boolean mergeEditorOpen() {
        return Window.getWindows().stream().anyMatch(window -> window.isShowing() && window.getScene() != null
                && window.getScene().getRoot() instanceof DialogPane pane
                && pane.getButtonTypes().stream().anyMatch(type -> "Mark resolved".equals(type.getText())));
    }

    private String statusText() {
        return lookup(".status-message").tryQuery()
                .map(node -> String.valueOf(((Labeled) node).getText())).orElse("");
    }

    /** Clicks {@code path}'s row in the Conflicted files list and returns the merge editor it opens. */
    private DialogPane clickConflict(String path) {
        Node row = from(lookup(".staging-conflicted-list")).lookup(path).query();
        clickOn(row);
        return RobotWait.untilDialogWithButton("Mark resolved");
    }

    /** Takes one side for the whole file, from the editor's Take menu. */
    private void takeWholeFile(DialogPane editor, String item) {
        clickOn(from(editor).lookup("Take").queryAs(Node.class));
        clickOn(from(lookup(".context-menu")).lookup(item).queryAs(Node.class));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean headIsAMerge() {
        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            RevCommit head = walk.parseCommit(git.getRepository().resolve("HEAD"));
            return head.getParentCount() == 2;
        } catch (Exception midCommit) {
            return false;
        }
    }

    // ------------------------------------------------------------------

    @Test
    void aStoppedMergeIsNamedListedApartAndWorkedThroughFileByFile() throws Exception {
        openWorkingTree();

        // The headline names both branches, as chips.
        RobotWait.untilOnFx("the banner never named the merge",
                () -> ("Merging feature/simple into " + mainBranch).equals(headline()));
        assertEquals(List.of("feature/simple", mainBranch), FxHarness.call(this::headlineChips));

        // The conflicts are listed on their own, not among the unstaged files.
        assertEquals(List.of(), FxHarness.call(() -> rows("staging-unstaged-list")),
                "a conflict is listed once, in the Conflicted files section");

        // Git refuses a commit while files are conflicted, so the composer says what is left.
        RobotWait.untilOnFx("the composer never said the conflicts come first",
                () -> "Resolve 2 conflicts first".equals(composerButton().getText()));
        assertTrue(FxHarness.call(() -> composerButton().isDisabled()), "nothing to commit until they are resolved");

        // The strip over the graph says the merge stopped, and on what.
        RobotWait.untilOnFx("the graph strip never showed", this::stripShowing);
        assertEquals("Merge of feature/simple into " + mainBranch + " stopped: 2 conflicting files",
                FxHarness.call(() -> tab.operationStrip().getText()));

        // A click on the row opens the merge editor.
        DialogPane first = clickConflict("a.txt");
        takeWholeFile(first, "Take theirs");
        clickOn(from(first).lookup("Mark resolved").queryAs(Node.class));
        RobotWait.until("a.txt was never resolved", () -> !service.getConflictingFiles().contains("a.txt"));

        // The next file is offered, not opened.
        RobotWait.untilOnFx("the next conflict was never offered", () -> nextOffer() != null);
        assertEquals("Resolve next: b.txt", FxHarness.call(() -> nextOffer().getText()));
        assertFalse(FxHarness.call(this::mergeEditorOpen), "the next file is offered, never opened by itself");

        // The resolved file is tagged among the staged, and gone from the conflicts.
        RobotWait.untilOnFx("a.txt never showed as resolved in the Staged list", () -> taggedResolved("a.txt"));
        assertEquals(List.of("b.txt"), FxHarness.call(() -> rows("staging-conflicted-list")));
        RobotWait.untilOnFx("the strip never caught up with one conflict left",
                () -> tab.operationStrip().getText().endsWith("stopped: 1 conflicting file"));

        // The offer opens the editor on the next file.
        interact(() -> nextOffer().fire());
        DialogPane second = RobotWait.untilDialogWithButton("Mark resolved");
        assertTrue(((Stage) second.getScene().getWindow()).getTitle().contains("b.txt"),
                () -> "the editor opened on " + ((Stage) second.getScene().getWindow()).getTitle());
        takeWholeFile(second, "Take theirs");
        clickOn(from(second).lookup("Mark resolved").queryAs(Node.class));
        RobotWait.until("b.txt was never resolved", () -> service.getConflictingFiles().isEmpty());

        // With none left, how to finish - and no offer of a file that is not there.
        RobotWait.untilOnFx("the status bar never said how to finish",
                () -> statusText().equals("All conflicts resolved - commit the merge"));
        RobotWait.untilOnFx("the strip never said the conflicts are resolved",
                () -> tab.operationStrip().getText().endsWith("all conflicts resolved - commit the merge"));
        assertNull(FxHarness.call(this::nextOffer), "nothing left to offer");

        // The composer names the merge as what it commits, once nothing is conflicted.
        RobotWait.untilOnFx("the composer never offered to commit the merge",
                () -> composerButton().getText().startsWith("Commit merge") && !composerButton().isDisabled());

        // Commit merge finishes it, and the strip goes with the merge.
        RobotWait.untilOnFx("Commit merge never became available",
                () -> bannerButton("Commit merge") != null && !bannerButton("Commit merge").isDisabled());
        interact(() -> bannerButton("Commit merge").fire());
        RobotWait.until("the merge commit never landed", this::headIsAMerge);
        RobotWait.untilOnFx("the graph strip outlived the merge", () -> !stripShowing());
        assertEquals("feature a\n", Files.readString(tempDir.resolve("a.txt")));
        assertEquals("feature b\n", Files.readString(tempDir.resolve("b.txt")));
    }

    /** The composer's commit button, found by its style class since its text changes. */
    private Button composerButton() {
        return FxHarness.lookupAll(tab.getContent(), "btn-primary").stream()
                .filter(n -> n instanceof Button b && b.isVisible())
                .map(Button.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no commit button"));
    }

    @Test
    void abortingTheMergeTakesTheStripAway() {
        openWorkingTree();
        RobotWait.untilOnFx("the graph strip never showed", this::stripShowing);

        Button abort = FxHarness.call(() -> bannerButton("Abort merge"));
        assertNotNull(abort, "the banner offers Abort");
        // Not interact(): the confirmation's nested event loop would hold it open.
        Platform.runLater(abort::fire);
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        interact(() -> ((Button) confirm.lookupButton(ButtonType.OK)).fire());

        RobotWait.until("the merge was never aborted", () -> !service.getOperationState().inProgress());
        RobotWait.untilOnFx("the graph strip outlived the merge", () -> !stripShowing());
    }

    @Test
    void theStripOpensTheConflicts() {
        RobotWait.untilOnFx("the graph never loaded past the WIP row",
                () -> tab.graphList().getItems().size() > 1 && tab.graphList().getItems().get(0).isWipNode());
        // Somewhere else first: a commit's details.
        interact(() -> tab.graphList().getSelectionModel().clearAndSelect(1));
        RobotWait.untilOnFx("the graph strip never showed", this::stripShowing);

        interact(() -> tab.operationStrip().fire());

        RobotWait.untilOnFx("the strip never opened the uncommitted changes",
                () -> tab.graphList().getSelectionModel().getSelectedItem() != null
                        && tab.graphList().getSelectionModel().getSelectedItem().isWipNode()
                        && rows("staging-conflicted-list").equals(List.of("a.txt", "b.txt")));
    }

    /** Mark resolved on a row asks first when the file still has markers; Enter keeps it conflicted. */
    @Test
    void markResolvedOnAFileStillHoldingMarkersAsksFirst() {
        openWorkingTree();
        interact(() -> liveConflicts().getSelectionModel().select("a.txt"));
        RobotWait.untilOnFx("a.txt's row never offered Mark resolved",
                () -> conflictRowButton("a.txt", "Mark resolved") != null);

        Button mark = FxHarness.call(() -> conflictRowButton("a.txt", "Mark resolved"));
        Platform.runLater(mark::fire);
        DialogPane ask = RobotWait.untilDialogWithButton("OK");
        assertTrue(ask.getHeaderText().contains("a.txt"), ask.getHeaderText());
        assertTrue(FxHarness.call(() -> ((Button) ask.lookupButton(ButtonType.CANCEL)).isDefaultButton()),
                "staging marker text is the mistake this stops: Enter cancels");
        interact(() -> ((Button) ask.lookupButton(ButtonType.CANCEL)).fire());
        RobotWait.drainGitWork();
        assertTrue(service.getConflictingFiles().contains("a.txt"), "cancelled: still conflicted");

        Button markAgain = FxHarness.call(() -> conflictRowButton("a.txt", "Mark resolved"));
        Platform.runLater(markAgain::fire);
        DialogPane again = RobotWait.untilDialogWithButton("OK");
        interact(() -> ((Button) again.lookupButton(ButtonType.OK)).fire());
        RobotWait.until("Mark resolved anyway never staged a.txt",
                () -> !service.getConflictingFiles().contains("a.txt"));
    }

    /** Mark all resolved lists the files that still hold markers, and only those. */
    @Test
    void markAllResolvedNamesTheFilesStillHoldingMarkers() throws Exception {
        openWorkingTree();
        // b.txt merged by hand, outside the editor; a.txt still as git left it.
        Files.writeString(tempDir.resolve("b.txt"), "merged by hand\n", StandardCharsets.UTF_8);

        Button all = FxHarness.call(() -> FxHarness.lookupAll(tab.getContent(), "mark-all-resolved").stream()
                .map(node -> (Button) node).findFirst().orElseThrow());
        Platform.runLater(all::fire);
        DialogPane ask = RobotWait.untilDialogWithButton("OK");
        List<String> listed = FxHarness.call(() -> FxHarness.lookupAll(ask, "jfx-affected-row").stream()
                .map(node -> ((Labeled) node).getText()).toList());
        assertEquals(List.of("a.txt"), listed, "only the file that still has markers is named");
        interact(() -> ((Button) ask.lookupButton(ButtonType.OK)).fire());

        RobotWait.until("Mark all resolved never staged both", () -> service.getConflictingFiles().isEmpty());
        RobotWait.untilOnFx("the Conflicted files section outlived its conflicts",
                () -> FxHarness.lookupAll(tab.getContent(), "staging-conflicted-section").stream()
                        .noneMatch(Node::isVisible));
    }

    @SuppressWarnings("unchecked")
    private ListView<String> liveConflicts() {
        return (ListView<String>) FxHarness.lookupAll(tab.getContent(), "staging-conflicted-list").stream()
                .filter(node -> node instanceof ListView<?>).findFirst().orElseThrow();
    }
}
