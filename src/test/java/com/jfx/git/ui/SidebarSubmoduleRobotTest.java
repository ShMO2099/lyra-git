package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import com.jfx.git.model.Submodule;
import com.jfx.git.model.SubmoduleState;

/**
 * Submodules driven from the sidebar against a real nested repository: adding the first one,
 * which the SUBMODULES section cannot do because it is hidden until one exists, and that
 * section's own init/update and deinitialise.
 *
 * <p>{@code submoduleSourceDir} stands in for a remote: a second, independent repository
 * this test owns, cloned in as a real submodule the same way a real remote URL would be.
 * JGit's {@code submoduleAdd} accepts a local path directly, so no bare-repo or transport
 * setup is needed the way {@code RepoToolbarRemoteRobotTest}'s push/pull tests require.
 *
 * <h2>Reaching context menus under Monocle Headless</h2>
 * As elsewhere this session, a robot right-click does not become a {@code ContextMenuEvent}
 * under Monocle Headless. The event is fired directly on the row, which is what {@code
 * ListCell.setContextMenu} itself listens for.
 */
class SidebarSubmoduleRobotTest extends ApplicationTest {

    /** Up front: FxHarness.call starts the harness on first use, which themes the toolkit. */
    @org.junit.jupiter.api.BeforeAll
    static void toolkitAndTheme() {
        FxHarness.start();
    }

    // NEVER, not the default: a submodule's pack files under .git/modules/... stay
    // memory-mapped via JGit's RepositoryCache even after this test's own Git wrapper
    // around them is closed, which Windows will not let JUnit delete afterwards - the
    // same benign OS-level race RepoToolbarRemoteRobotTest already worked around.
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tempDir;

    @TempDir
    Path submoduleSourceDir;

    private GitService service;
    private LyraApp app;
    private RepoTab tab;
    /** The application window, so a dialog lookup can exclude everything it contains. */
    private Stage mainWindow;
    private String submoduleUrl;

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
        commit("a.txt", "original a", "add a");

        try (Git git = Git.init().setDirectory(submoduleSourceDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
            Files.writeString(submoduleSourceDir.resolve("sub.txt"), "v1", StandardCharsets.UTF_8);
            git.add().addFilepattern("sub.txt").call();
            git.commit().setMessage("v1").call();
            Files.writeString(submoduleSourceDir.resolve("sub.txt"), "v2", StandardCharsets.UTF_8);
            git.add().addFilepattern("sub.txt").call();
            git.commit().setMessage("v2").call();
        }
        submoduleUrl = submoduleSourceDir.toUri().toString();

        app = new LyraApp();
        tab = new RepoTab(app, "test", service);
        TabPane pane = new TabPane(tab);
        useTabStrip(app, pane);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        mainWindow = stage;
        interact(() -> tab.refresh());
        settle();
    }

    @AfterEach
    void tidyUp() {
        // A click that starts git work returns before the work does; wait for it, so the
        // backend is not closed - and @TempDir not deleted - under a running command.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    /**
     * Gives an app that was never started a tab strip, so the command palette finds this
     * test's tab as the active repository. As in {@code IncompleteQaCommandPaletteRobotTest}.
     */
    private static void useTabStrip(LyraApp app, TabPane tabs) {
        try {
            java.lang.reflect.Field field = LyraApp.class.getDeclaredField("tabPane");
            field.setAccessible(true);
            field.set(app, tabs);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not give the app its tab strip", e);
        }
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Refreshes, and waits for everything the refresh queued on the git lane, then for its
     * results to be applied on the FX thread. Until then the SUBMODULES section is still
     * hidden - the refresh is what reveals it - so its header cannot be clicked yet.
     */
    private void refreshAndWait() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** A file's content, for a condition; null while it cannot be read. */
    private String contentOf(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException notYet) {
            return null;
        }
    }

    /**
     * The sidebar section headed {@code title}.
     *
     * <p>Every section carries its heading as a graphic - a label plus a count badge -
     * rather than as the pane's text. Some of them used to set both, which drew the
     * heading twice ("STASHES 0 STASHES"); now that only the graphic is set, a lookup by
     * {@code getText()} alone finds nothing.
     */
    private javafx.scene.control.TitledPane section(String title) {
        return FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .filter(n -> n instanceof javafx.scene.control.TitledPane tp
                        && headingOf(tp).startsWith(title))
                .map(n -> (javafx.scene.control.TitledPane) n)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + title + "\" section found"));
    }

    /** A pane's heading, wherever it keeps it: its text, or the label in its graphic. */
    private static String headingOf(javafx.scene.control.TitledPane pane) {
        if (pane.getText() != null && !pane.getText().isBlank()) {
            return pane.getText();
        }
        if (pane.getGraphic() instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                if (child instanceof javafx.scene.control.Label label
                        && label.getText() != null && !label.getText().isBlank()) {
                    return label.getText();
                }
            }
        }
        return "";
    }

    private void expandSection(String title) {
        javafx.scene.control.TitledPane pane = section(title);
        clickOn(pane);
        RobotWait.untilOnFx("the " + title + " section never expanded", pane::isExpanded);
    }

    /** Whether the section headed {@code title} is on screen; read on the FX thread. */
    private boolean sectionShown(String title) {
        return FxHarness.call(() -> section(title).isVisible() && section(title).isManaged());
    }

    /** The LOCAL section's tree, first among the two ".branch-tree" controls the sidebar builds. */
    private javafx.scene.control.Control localTree() {
        return FxHarness.call(() -> (javafx.scene.control.Control)
                FxHarness.lookupAll(tab.getContent(), "branch-tree").get(0));
    }

    /** Opens the LOCAL section's own menu: the tree's, not a branch row's. */
    private void openLocalSectionMenu() {
        javafx.scene.control.Control tree = localTree();
        interact(() -> Event.fireEvent(tree, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        RobotWait.untilOnFx("the LOCAL section's menu never opened", () -> tree.getContextMenu().isShowing());
    }

    /**
     * Fills the add-submodule dialog with this test's source repository at {@code path}, and
     * adds it. The add runs with the form open, which closes once it worked.
     */
    private void answerAddSubmodule(String path) {
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("OK");
        assertEquals("Add submodule", FxHarness.call(() ->
                ((Stage) dialog.getScene().getWindow()).getTitle()));
        setText(dialogField(0), submoduleUrl);
        setText(dialogField(1), path);
        clickOn("Add submodule");
        RobotWait.untilOnFx("the form never closed after the submodule was added",
                () -> dialog.getScene() == null || !dialog.getScene().getWindow().isShowing());
    }

    private void openMenuOn(Node row) {
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
    }

    /** @see FxHarness#dialogField(javafx.stage.Window, int) */
    private Node dialogField(int index) {
        return FxHarness.dialogField(mainWindow, index);
    }

    private void setText(Node field, String text) {
        interact(() -> ((javafx.scene.control.TextInputControl) field).setText(text));
    }

    /**
     * The list row whose text contains {@code fragment}. Waits for it: the rows come from a
     * refresh that runs off the FX thread, and are only laid out once the section is open.
     */
    private Node rowContaining(String fragment) {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no row containing \"" + fragment + "\" appeared", () -> {
            row[0] = FxHarness.lookupAll(tab.getContent(), "list-cell").stream()
                    .filter(n -> n instanceof javafx.scene.control.ListCell<?> cell
                            && cell.getText() != null && cell.getText().contains(fragment))
                    .findFirst()
                    .orElse(null);
            return row[0] != null;
        });
        return row[0];
    }

    private Submodule submodule(String path) {
        return service.getSubmodules().stream()
                .filter(s -> s.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no submodule at " + path
                        + " - have " + service.getSubmodules()));
    }

    // ------------------------------------------------------------------

    /**
     * The SUBMODULES section hides itself while the repository has none, so its own "Add
     * submodule…" cannot add the first one. This test used to reach that menu anyway, by
     * firing the menu event on the hidden section's list, which no user can do. The first
     * submodule comes from the LOCAL section's menu.
     *
     * <p>Waits for the dialog and for the submodule to be recorded, not a fixed time: with a
     * 300ms sleep this failed now and then, while the clone was still running on the git lane.
     */
    @Test
    void theFirstSubmoduleIsAddedFromTheLocalSectionMenu() throws Exception {
        refreshAndWait();
        assertFalse(sectionShown("SUBMODULES"), "a repository with no submodules has no SUBMODULES section");

        openLocalSectionMenu();
        clickOn("Add submodule…");
        answerAddSubmodule("libs/sub");
        // submodule() throws until libs/sub is registered, which reads as "not yet"; CURRENT
        // needs the clone checked out and its commit recorded in the index, the add's last step.
        RobotWait.until("the submodule was never added", () ->
                Files.isRegularFile(tempDir.resolve(".gitmodules"))
                        && submodule("libs/sub").state() == SubmoduleState.CURRENT);

        assertTrue(Files.isRegularFile(tempDir.resolve(".gitmodules")),
                "a real .gitmodules file should have been written");
        String gitmodules = Files.readString(tempDir.resolve(".gitmodules"));
        assertTrue(gitmodules.contains("libs/sub"), ".gitmodules should record the path: " + gitmodules);

        assertEquals("v2", Files.readString(tempDir.resolve("libs/sub/sub.txt")),
                "the submodule should be a real checkout at the source's latest commit");
        assertEquals(SubmoduleState.CURRENT, submodule("libs/sub").state());

        // The add's own refresh is what reveals the section, now that there is one to list.
        RobotWait.until("the SUBMODULES section never appeared", () -> sectionShown("SUBMODULES"));
        expandSection("SUBMODULES");
        rowContaining("libs/sub");
    }

    @Test
    void theCommandPaletteAddsASubmoduleWhileThereAreNone() throws Exception {
        refreshAndWait();
        assertFalse(sectionShown("SUBMODULES"), "a repository with no submodules has no SUBMODULES section");

        CommandPalette[] palette = new CommandPalette[1];
        interact(() -> {
            palette[0] = new CommandPalette(app);
            palette[0].show();
        });
        RobotWait.untilOnFx("the palette never opened", () -> palette[0].isShowing());
        javafx.scene.control.TextField search = FxHarness.call(() ->
                (javafx.scene.control.TextField) palette[0].getScene().lookup(".palette-search"));
        javafx.scene.control.ListView<?> results = FxHarness.call(() ->
                (javafx.scene.control.ListView<?>) palette[0].getScene().lookup(".list-dark"));

        interact(() -> search.setText("add submodule"));
        RobotWait.untilOnFx("the palette did not offer to add a submodule", () ->
                !results.getItems().isEmpty()
                        && results.getItems().get(0) instanceof CommandPalette.PaletteItem first
                        && first.displayText().equals("Repository: Add submodule…"));
        // Not inside interact(): the command opens a dialog that waits for an answer, and
        // interact() would wait with it.
        javafx.application.Platform.runLater(() -> Event.fireEvent(search, new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER,
                false, false, false, false)));
        answerAddSubmodule("libs/sub");

        RobotWait.until("the submodule was never added", () ->
                submodule("libs/sub").state() == SubmoduleState.CURRENT);
        assertEquals("v2", Files.readString(tempDir.resolve("libs/sub/sub.txt")));
        RobotWait.until("the SUBMODULES section never appeared", () -> sectionShown("SUBMODULES"));
    }

    /**
     * A folder that already has files in it cannot take a submodule: said under the folder
     * as it is typed, with Add waiting, and gone again for a folder that is free.
     */
    @Test
    void aFolderWithFilesInItIsRefusedAsItIsTyped() throws Exception {
        Files.createDirectories(tempDir.resolve("libs/taken"));
        Files.writeString(tempDir.resolve("libs/taken/notes.txt"), "mine", StandardCharsets.UTF_8);
        refreshAndWait();

        openLocalSectionMenu();
        clickOn("Add submodule…");
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("OK");
        setText(dialogField(0), submoduleUrl);
        setText(dialogField(1), "libs/taken");
        FxHarness.run(() -> {
            assertEquals("libs/taken already exists and has files in it.",
                    ManualQaCloneValidationTest.errorUnder(dialog, SidebarPane.SUBMODULE_PATH_ID).getText());
            assertTrue(dialog.lookupButton(javafx.scene.control.ButtonType.OK).isDisabled(),
                    "Add waits for a folder git will use");
        });

        setText(dialogField(1), "libs/sub");
        FxHarness.run(() -> {
            assertEquals("", ManualQaCloneValidationTest.errorUnder(dialog, SidebarPane.SUBMODULE_PATH_ID).getText());
            assertFalse(dialog.lookupButton(javafx.scene.control.ButtonType.OK).isDisabled());
            ((javafx.scene.control.Button) dialog.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
        });
        assertFalse(Files.exists(tempDir.resolve(".gitmodules")), "Cancel adds nothing");
    }

    @Test
    void deinitialisingRemovesTheWorkingCopyButKeepsGitmodules() throws Exception {
        assertTrue(service.addSubmodule(submoduleUrl, "libs/sub").isOk());
        // JGit's own submoduleDeinit() cannot act on an addition that only exists in the
        // index - see GitService.deinitSubmodule's own comment - so this test's setup
        // commits it first, the realistic shape anyway: deinitialising an already-
        // established submodule in a later session, not one just added this same one.
        assertTrue(service.stageFile(".gitmodules").isOk());
        assertTrue(service.stageFile("libs/sub").isOk());
        assertTrue(service.commit("add submodule", false).isOk());
        refreshAndWait();
        expandSection("SUBMODULES");

        Node row = rowContaining("libs/sub");
        openMenuOn(row);
        clickOn("Deinitialise…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Deinitialise");
        RobotWait.until("the submodule was never deinitialised", () ->
                !Files.exists(tempDir.resolve("libs/sub/sub.txt"))
                        && submodule("libs/sub").state() == SubmoduleState.UNINITIALIZED);

        assertFalse(Files.exists(tempDir.resolve("libs/sub/sub.txt")),
                "the working copy's content should be gone");
        String gitmodules = Files.readString(tempDir.resolve(".gitmodules"));
        assertTrue(gitmodules.contains("libs/sub"),
                ".gitmodules must still record the submodule - deinit is not delete: " + gitmodules);
        assertEquals(SubmoduleState.UNINITIALIZED, submodule("libs/sub").state());
    }

    /**
     * Confirms the real defect this session found and fixed: JGit's own {@code
     * submoduleDeinit()} throws a bare {@code NullPointerException} - "Cannot invoke
     * SubmoduleWalk.getHead() because \"w\" is null" - for a submodule that was added
     * but never committed, rather than the clear, actionable message this now returns
     * instead. Deliberately calls the service directly rather than through the UI: the
     * point is the real message {@code GitService} returns, not another dialog round trip.
     */
    @Test
    void deinitialisingAnUncommittedSubmoduleFailsWithAClearMessageNotAStackTrace() throws Exception {
        assertTrue(service.addSubmodule(submoduleUrl, "libs/sub").isOk());

        var result = service.deinitSubmodule("libs/sub", false);

        assertFalse(result.isOk());
        assertTrue(result.getMessage().toLowerCase(java.util.Locale.ROOT).contains("commit"),
                "the message should say what to do, not leak a JGit internal null "
                        + "pointer exception: " + result.getMessage());
        assertTrue(Files.exists(tempDir.resolve("libs/sub/sub.txt")),
                "a failed deinit must leave the real working copy untouched");
    }

    @Test
    void initUpdateBringsAnOutOfDateSubmoduleCurrent() throws Exception {
        assertTrue(service.addSubmodule(submoduleUrl, "libs/sub").isOk());
        Path submoduleDir = tempDir.resolve("libs/sub");

        // Move the submodule's own checkout back to its first commit by hand, without
        // touching the superproject's recorded pointer - exactly what "out of date"
        // means: the checked-out revision and the one recorded in the index disagree.
        try (Git subGit = Git.open(submoduleDir.toFile())) {
            // JGit's log walks newest first, so the last one seen is "v1", the oldest.
            org.eclipse.jgit.revwalk.RevCommit oldest = null;
            for (var c : subGit.log().call()) {
                oldest = c;
            }
            subGit.checkout().setName(oldest.getName()).call();
        }
        assertEquals("v1", Files.readString(submoduleDir.resolve("sub.txt")));

        refreshAndWait();
        assertEquals(SubmoduleState.OUT_OF_DATE, submodule("libs/sub").state());
        expandSection("SUBMODULES");

        Node row = rowContaining("libs/sub");
        openMenuOn(row);
        clickOn("Init / update");
        RobotWait.until("the submodule was never updated", () ->
                "v2".equals(contentOf(submoduleDir.resolve("sub.txt")))
                        && submodule("libs/sub").state() == SubmoduleState.CURRENT);

        assertEquals("v2", Files.readString(submoduleDir.resolve("sub.txt")),
                "updating should have moved the real checkout back to the recorded commit");
        assertEquals(SubmoduleState.CURRENT, submodule("libs/sub").state());
    }

    /**
     * Changes inside a submodule's working copy are why git refuses to deinitialise it, and
     * the second question says so - from the engine's refusal, not from reading its words.
     */
    @Test
    void aModifiedSubmoduleIsDeinitialisedOnlyAfterSayingItHasChanges() throws Exception {
        assertTrue(service.addSubmodule(submoduleUrl, "libs/sub").isOk());
        assertTrue(service.stageFile(".gitmodules").isOk());
        assertTrue(service.stageFile("libs/sub").isOk());
        assertTrue(service.commit("add submodule", false).isOk());
        Files.writeString(tempDir.resolve("libs/sub/sub.txt"), "changed in place", StandardCharsets.UTF_8);
        refreshAndWait();
        expandSection("SUBMODULES");

        openMenuOn(rowContaining("libs/sub"));
        clickOn("Deinitialise…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Deinitialise");
        untilDialogSubtitled("Its working copy has changes that are not committed");
        assertTrue(Files.exists(tempDir.resolve("libs/sub/sub.txt")), "nothing goes before the second yes");

        clickOn("Deinitialise anyway");
        RobotWait.until("the submodule was never deinitialised", () ->
                !Files.exists(tempDir.resolve("libs/sub/sub.txt")));
    }

    /**
     * A URL that is no repository is git's refusal, not the form's own check - and the
     * engine says which field it is about, so it is said under the URL, with the folder
     * left clear and the form still open to fix it.
     */
    @Test
    void aRepositoryThatIsNotThereIsSaidUnderTheUrl() throws Exception {
        refreshAndWait();
        openLocalSectionMenu();
        clickOn("Add submodule…");
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("OK");
        setText(dialogField(0), tempDir.resolveSibling(tempDir.getFileName() + "-nowhere").toUri().toString());
        setText(dialogField(1), "libs/sub");
        clickOn("Add submodule");

        RobotWait.untilOnFx("git's refusal never appeared under the URL", () ->
                !ManualQaCloneValidationTest.errorUnder(dialog, SidebarPane.SUBMODULE_URL_ID).getText().isEmpty());
        FxHarness.run(() -> {
            assertEquals("", ManualQaCloneValidationTest.errorUnder(dialog, SidebarPane.SUBMODULE_PATH_ID).getText(),
                    "the folder is fine");
            assertTrue(dialog.getScene().getWindow().isShowing(), "the form stays open to fix the URL");
            ((javafx.scene.control.Button) dialog.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
        });
    }

    /**
     * Waits for a showing dialog whose subtitle - the line under its title, which says
     * why it is asking - reads {@code text}.
     */
    private static void untilDialogSubtitled(String text) {
        RobotWait.untilOnFx("no dialog subtitled \"" + text + "\" appeared", () ->
                new java.util.ArrayList<>(Window.getWindows()).stream()
                        .filter(w -> w.isShowing() && w.getScene() != null
                                && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
                        .map(w -> w.getScene().getRoot().lookup(".jfx-dialog-subtitle"))
                        .anyMatch(flow -> flow instanceof javafx.scene.text.TextFlow runs
                                && text.equals(runs.getChildren().stream()
                                        .filter(javafx.scene.text.Text.class::isInstance)
                                        .map(run -> ((javafx.scene.text.Text) run).getText())
                                        .collect(java.util.stream.Collectors.joining()))));
    }
}
