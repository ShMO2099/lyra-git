package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.Favorites;
import com.jfx.git.core.Settings;
import com.jfx.git.core.TabSession;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real application shell - {@link LyraApp#start}, not a bare {@link RepoTab} - so the
 * global keyboard map ({@link com.jfx.git.ui.Shortcuts}) and the command palette are
 * exercised as they are actually wired, not reconstructed piecemeal. Every other robot test
 * this session builds a {@code RepoTab} directly, which is right for what those tests are
 * proving but never reaches {@code buildShortcuts} or the tab strip at all.
 *
 * <h2>Reaching a real repository without a native file chooser</h2>
 * "Open Repository…" is a real {@code DirectoryChooser}, which Monocle Headless cannot
 * drive - there is no OS-level picker to click through. {@code LyraApp.start} already has
 * a way in that does not need one: with no saved tab session, it falls back to {@code
 * Settings}'s {@code repo.last} key and opens that directory directly. Setting it before
 * {@code start} runs reaches a real, fully-wired {@code RepoTab} through the app's own
 * normal startup path rather than a private method reached by reflection.
 *
 * <h2>Why the settings file is safe to touch here</h2>
 * {@code lyra.settings} is already redirected to {@code target/test-settings.properties}
 * for the whole suite (see {@code Settings}'s own Javadoc and the surefire configuration in
 * {@code pom.xml}) - this never touches a real user's machine. It is shared across this
 * class's own tests within one JVM, though: {@link #tidyUp} clears {@code TabSession} and
 * {@code repo.last} afterwards so one test's temp directory - about to be deleted - is never
 * something a later test tries to restore.
 *
 * <h2>Modifier combinations without TestFX's own {@code push}</h2>
 * {@code push(KeyCode.CONTROL, KeyCode.P)} throws {@code AbstractMethodError}: Monocle
 * Headless's {@code MonocleApplication} does not implement Glass's {@code
 * _getKeyCodeForChar}, which TestFX's robot-level key dispatch needs to resolve a modifier
 * combination. This is a gap in Monocle itself, not in this application - confirmed by every
 * one of this class's shortcuts failing identically, including the simplest, and by every
 * other keyboard interaction this session ({@code write(...)} for typed text) working
 * without incident. The fix already used elsewhere this session for a native gesture Monocle
 * cannot produce - firing the real {@link KeyEvent} {@link com.jfx.git.ui.Shortcuts}
 * actually listens for, directly - applies the same way here.
 */
class AppShortcutsRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private LyraApp app;
    private Stage stage;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        Files.writeString(tempDir.resolve("a.txt"), "original a", StandardCharsets.UTF_8);

        // A clean slate: no restored session, no stale repo.last from an earlier test's
        // now-deleted temp directory.
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", tempDir.toAbsolutePath().toString());

        app = new LyraApp();
        app.start(stage);
    }

    /**
     * The tab arrives a pulse after start() - the window is drawn first - so it is waited
     * for here, off the FX thread, rather than looked for inside start().
     */
    @org.junit.jupiter.api.BeforeEach
    void openTheRepositoryTab() {
        RobotWait.untilOnFx("repo.last should have opened a real repo tab, not the launchpad",
                () -> app.getActiveRepoTab() != null && app.getActiveRepoTab().getGitService().isOpen());
        RobotWait.drainGitWork();
        // The fallback opens the directory, but the first real commit still has to be
        // made through the repository this test owns, once the tab exists to see it.
        RepoTab tab = FxHarness.call(app::getActiveRepoTab);
        assertNotNull(tab, "repo.last should have opened a real repo tab, not the launchpad");
        var service = tab.getGitService();
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("add a", false).isOk());
        interact(tab::refresh);
        settle();
    }

    @AfterEach
    void tidyUp() {
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", null);
        Settings.put("repo.favorites", null);
        // Shortcuts and palette commands submit git work and return; the repository must
        // not be closed - and @TempDir not deleted - under a command still running.
        RobotWait.drainGitWork();
        List<RepoTab> repoTabs = app == null ? List.of() : FxHarness.call(() ->
                app.openRepositories().stream()
                        .map(LyraApp.OpenRepository::tab)
                        .filter(RepoTab.class::isInstance)
                        .map(RepoTab.class::cast)
                        .toList());
        if (!repoTabs.isEmpty()) {
            interact(() -> repoTabs.forEach(RepoTab::close));
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    /**
     * Refreshes the active tab and waits for it to land: its queries run on the git lane
     * and report back on the FX thread. Git undo reads the reflog this refresh loads, and
     * the staging list it fills is what the editing test clicks.
     */
    private void refreshTab() {
        interact(() -> tab().refresh());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Whether {@code path} is in the index. Polled against the real repository rather
     * than the panel: what is being asserted is that the command ran the git operation,
     * and the panel is a separate question with its own refresh.
     */
    private boolean isStaged(String path) {
        return tab().getGitService().getStagedFiles().contains(path);
    }

    /** Whether the diff viewer holds a file it can edit - the Edit button is up and enabled. */
    private boolean editableFileLoaded() {
        return from(tab().getContent()).lookup(".diff-tool-button").queryAll().stream()
                .anyMatch(n -> n instanceof Button b && b.getText().startsWith("Edit")
                        && b.isVisible() && !b.isDisabled());
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

    private RepoTab tab() {
        return app.getActiveRepoTab();
    }

    /**
     * Fires a real KEY_PRESSED - Ctrl(+Shift) held - directly on {@code target}. The settle
     * covers shortcuts that only change the UI; a test whose shortcut starts git work
     * waits for that work's outcome itself.
     */
    private void fireKey(Node target, KeyCode code, boolean control, boolean shift) {
        interact(() -> Event.fireEvent(target, new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false)));
        settle();
    }

    /** A global app shortcut - {@link com.jfx.git.ui.Shortcuts} filters at the main scene. */
    private void fireAppShortcut(KeyCode code, boolean shift) {
        fireKey(stage.getScene().getRoot(), code, true, shift);
    }

    /** Ctrl+Alt held - the Favorites shortcuts, which need Alt as well as Ctrl. */
    private void fireAppShortcutAlt(KeyCode code) {
        interact(() -> Event.fireEvent(stage.getScene().getRoot(), new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", code, false, true, true, false)));
        settle();
    }

    // ------------------------------------------------------------------

    @Test
    void tidyUpClosesRepositoryWhenWelcomeTabIsActive() {
        RepoTab original = tab();
        interact(() -> app.openWelcomeTab());
        settle();
        assertNull(app.getActiveRepoTab(), "the welcome tab has no active repository");

        try {
            tidyUp();
            assertFalse(original.getGitService().isOpen(),
                    "cleanup must close the repository even when another tab is selected");
        } finally {
            // Keep a failed cleanup assertion from leaking the timer under test.
            if (original.getGitService().isOpen()) {
                interact(original::close);
            }
        }
    }

    @Test
    void ctrlPOpensTheCommandPalette() {
        fireAppShortcut(KeyCode.P, false);

        Window palette = Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof CommandPalette)
                .findFirst()
                .orElse(null);
        assertNotNull(palette, "Ctrl+P should have opened the command palette");
        assertNotNull(lookup(".palette-search").tryQuery().orElse(null),
                "the palette's search field should be visible");
    }

    @Test
    void runningACommandFromThePaletteReachesRealGit() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "changed a", StandardCharsets.UTF_8);
        refreshTab();
        assertTrue(tab().getGitService().getStagingState().unstaged().contains("a.txt"));

        fireAppShortcut(KeyCode.P, false);
        Node search = lookup(".palette-search").query();
        clickOn(search);
        write("Stage all");
        settle();
        fireKey(search, KeyCode.ENTER, false, false);
        RobotWait.until("a.txt never reached the index", () -> isStaged("a.txt"));

        assertTrue(tab().getGitService().getStagedFiles().contains("a.txt"),
                "choosing \"Git: Stage all\" from the palette should reach the real index");
    }

    @Test
    void editCurrentFileFromThePaletteSwitchesTheOpenFileIntoTheEditor() throws Exception {
        interact(() -> tab().getRightPanel().openWorkingTreeFile("a.txt"));
        // The file is read on the git lane; editing it needs it on screen first.
        RobotWait.untilOnFx("a.txt never loaded into the viewer", this::editableFileLoaded);

        fireAppShortcut(KeyCode.P, false);
        Node search = lookup(".palette-search").query();
        clickOn(search);
        write("Edit current file");
        settle();
        fireKey(search, KeyCode.ENTER, false, false);

        assertTrue(tab().isEditingFile(),
                "choosing \"File: Edit current file\" should switch the open file into the editor");
    }

    @Test
    void switchThemeFromThePaletteFlipsToTheOppositeTheme() throws Exception {
        com.jfx.git.ui.ThemeManager.Theme before = com.jfx.git.ui.ThemeManager.current();

        fireAppShortcut(KeyCode.P, false);
        Node search = lookup(".palette-search").query();
        clickOn(search);
        write("Switch theme");
        settle();
        fireKey(search, KeyCode.ENTER, false, false);

        assertNotEquals(before, com.jfx.git.ui.ThemeManager.current(),
                "choosing \"View: Switch theme\" should flip to the other theme");

        // Leave global state as this class found it, so test order cannot matter. On
        // the FX thread and settled before returning: ThemeManager.apply repaints every
        // registered scene, including the still-live commit graph, and tidyUp() closing
        // the repository must not race that repaint.
        interact(() -> com.jfx.git.ui.ThemeManager.apply(before));
        settle();
    }

    @Test
    void globalShortcutStagesAllWithoutOpeningThePalette() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "changed a", StandardCharsets.UTF_8);
        refreshTab();

        fireAppShortcut(KeyCode.S, true);

        // The shortcut submits stageAll to the repository lane and returns; the index has
        // not been written when the key handler finishes. Asserting straight afterwards
        // was a race that happened to win on an idle machine and lost whenever the lane
        // had anything queued ahead of it. Waiting on the condition is both correct and
        // faster than a fixed pause.
        RobotWait.until("a.txt never reached the index", () -> isStaged("a.txt"));
        assertTrue(isStaged("a.txt"),
                "Ctrl+Shift+S should stage everything without the palette being involved");
    }

    @Test
    void ctrlTOpensANewWelcomeTab() {
        int before = tabCount();

        fireAppShortcut(KeyCode.T, false);

        assertEquals(before + 1, tabCount(), "Ctrl+T should have opened one more tab");
    }

    @Test
    void ctrlAltDigitOpensTheFirstFavoritedRepository(@TempDir Path favoriteDir) throws Exception {
        try (Git git = Git.init().setDirectory(favoriteDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();
        }
        Favorites.add(favoriteDir.toFile());

        fireAppShortcutAlt(KeyCode.DIGIT1);
        // The tab appears at once, but the repository is opened on a background thread.
        RobotWait.until("the favorited repository never opened", () -> activeTabIsOpenOn(favoriteDir));

        RepoTab active = app.getActiveRepoTab();
        assertNotNull(active, "Ctrl+Alt+1 should have opened a real repo tab");
        assertEquals(favoriteDir.toFile().getCanonicalFile(),
                active.getGitService().getWorkTree().getCanonicalFile(),
                "should have opened the favorited repository, not stayed on the original tab");
    }

    @Test
    void ctrlAltDigitWithNoThatManyFavoritesDoesNothing() {
        assertTrue(Favorites.load().isEmpty(), "precondition: nothing favorited yet");
        RepoTab before = app.getActiveRepoTab();

        fireAppShortcutAlt(KeyCode.DIGIT1);

        assertSame(before, app.getActiveRepoTab(), "no first favorite exists, so nothing should have happened");
    }

    @Test
    void ctrlDigitSwitchesBackToTheRealRepoTabFromTheWelcomeTab() {
        interact(() -> app.openWelcomeTab());
        settle();
        assertNull(app.getActiveRepoTab(), "the welcome tab is not a repo tab");

        fireAppShortcut(KeyCode.DIGIT1, false);

        assertNotNull(app.getActiveRepoTab(),
                "Ctrl+1 should have switched back to the first (real repo) tab");
    }

    @Test
    void ctrlTabAndCtrlShiftTabCycleBetweenRealTabs() {
        interact(() -> app.openWelcomeTab());
        settle();
        assertNull(app.getActiveRepoTab(), "starts on the welcome tab, not the repo tab");

        // Wraps forward from the last tab back to the first.
        fireAppShortcut(KeyCode.TAB, false);
        assertNotNull(app.getActiveRepoTab(), "Ctrl+Tab should have wrapped to the repo tab");

        fireAppShortcut(KeyCode.TAB, true);
        assertNull(app.getActiveRepoTab(),
                "Ctrl+Shift+Tab should have gone back to the welcome tab");
    }

    /**
     * Settings is a tab beside the repository, not a window over it: Ctrl+, adds it to the
     * strip, Ctrl+Tab passes through it, and Ctrl+1 - which numbers repositories only -
     * goes straight back to the repository.
     */
    @Test
    void ctrlCommaOpensSettingsAsATabBesideTheRepository() {
        int before = tabCount();
        long windowsBefore = interactCount();

        fireAppShortcut(KeyCode.COMMA, false);

        assertEquals(before + 1, tabCount(), "Settings should have joined the strip");
        assertTrue(app.getActiveRepoTab() == null, "and be the tab in front");
        assertEquals(windowsBefore, interactCount(), "without a window of its own");

        fireAppShortcut(KeyCode.COMMA, false);
        assertEquals(before + 1, tabCount(), "asked again, the same tab comes forward");

        fireAppShortcut(KeyCode.DIGIT1, false);
        assertNotNull(app.getActiveRepoTab(), "Ctrl+1 is the first repository, not the first tab");

        fireAppShortcut(KeyCode.TAB, false);
        assertNull(app.getActiveRepoTab(), "Ctrl+Tab cycles onto the Settings tab like any other");
        fireAppShortcut(KeyCode.TAB, false);
        assertNotNull(app.getActiveRepoTab(), "and wraps back to the repository");
    }

    /** Showing windows - the main one and any dialog - counted on the FX thread. */
    private long interactCount() {
        long[] count = new long[1];
        interact(() -> count[0] = Window.getWindows().stream().filter(Window::isShowing).count());
        return count[0];
    }

    /** Whether the active tab has finished opening {@code dir}; an I/O failure reads as "not yet". */
    private boolean activeTabIsOpenOn(Path dir) {
        try {
            RepoTab active = app.getActiveRepoTab();
            return active != null && active.getGitService() != null
                    && active.getGitService().getWorkTree() != null
                    && dir.toFile().getCanonicalFile()
                            .equals(active.getGitService().getWorkTree().getCanonicalFile());
        } catch (java.io.IOException notYet) {
            return false;
        }
    }

    /**
     * The main strip's tab count. The strip is the outermost tab pane: the activity log
     * tab has one of its own inside it, and which of the two a bare lookup returns first
     * is not something to rely on.
     */
    private int tabCount() {
        for (Node node : lookup(".tab-pane").queryAll()) {
            boolean nested = false;
            for (Node up = node.getParent(); up != null; up = up.getParent()) {
                nested |= up instanceof javafx.scene.control.TabPane;
            }
            if (!nested && node.getScene() == stage.getScene()) {
                return ((javafx.scene.control.TabPane) node).getTabs().size();
            }
        }
        throw new AssertionError("no tab strip in the main window");
    }

    // ------------------------------------------------------------------
    // Ctrl+Z/Ctrl+Shift+Z standing aside for an editable text control
    // ------------------------------------------------------------------

    @Test
    void ctrlZWithNoTextControlFocusedStillDrivesGitUndo() throws Exception {
        String beforeMessage = tab().getGitService().getHeadCommitMessage();
        Files.writeString(tempDir.resolve("b.txt"), "b", StandardCharsets.UTF_8);
        assertTrue(tab().getGitService().stageFile("b.txt").isOk());
        assertTrue(tab().getGitService().commit("second commit", false).isOk());
        // Undo walks the reflog this refresh reloads; without it there is nothing to undo.
        refreshTab();
        assertEquals("second commit", tab().getGitService().getHeadCommitMessage());

        // Nothing text-editable has focus - the tab pane's own root will do.
        fireAppShortcut(KeyCode.Z, false);
        // Two hops on the git lane: the dirty-tree check, then the reset.
        RobotWait.until("Ctrl+Z never undid the commit",
                () -> beforeMessage.equals(tab().getGitService().getHeadCommitMessage()));

        assertEquals(beforeMessage, tab().getGitService().getHeadCommitMessage(),
                "Ctrl+Z with no text control focused must still drive git-history undo");
    }

    /**
     * The node with this text that is genuinely displayed, of however many carry it.
     *
     * <p>A {@code ListView} keeps recycled cells in the scene graph with their old text
     * and no scene of their own, so a text lookup can hand back something that cannot be
     * clicked. Asking for screen bounds is what distinguishes a cell on screen from one
     * the flow has parked.
     */
    private Node onScreen(String text) {
        // Waited for rather than assumed: the panel is filled from a background query, so
        // "settle() then look" races whatever is still in flight. Polled on the FX thread,
        // since the answer is read from a scene graph that thread is re-laying out.
        Node[] found = new Node[1];
        RobotWait.untilOnFx("no node showing \"" + text + "\"",
                () -> (found[0] = showing(text)) != null);
        return found[0];
    }

    /** The node with this text that is genuinely on screen right now, or null. */
    private Node showing(String text) {
        return from(tab().getContent()).lookup(text).queryAll().stream()
                .filter(n -> n.getScene() != null && n.isVisible()
                        && n.localToScreen(n.getBoundsInLocal()) != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * Clicks the node showing {@code text}, re-resolving it if the flow recycles it first.
     *
     * <p>{@link #onScreen} picks a cell that is on screen at that instant, and the click
     * that follows asks it for its bounds a moment later. A background refresh landing in
     * between - the sidebar's model load completing, say - re-lays the list out, and the
     * cell that was picked is parked with a null scene before it can be clicked. TestFX
     * then fails inside the robot rather than at a lookup, which reads like a product
     * fault and is not one.
     *
     * <p>So resolve and click as one attempt, and take a fresh cell if that attempt lost
     * the race. A node that genuinely never appears still fails, from {@code onScreen}.
     */
    private void clickOnScreen(String text) {
        for (int attempt = 0; ; attempt++) {
            Node node = onScreen(text);
            try {
                clickOn(node);
                return;
            } catch (RuntimeException recycledUnderneath) {
                if (attempt == 4) {
                    throw recycledUnderneath;
                }
                settle();
            }
        }
    }

    @Test
    void ctrlZWhileEditingUndoesTheTypedTextNotAGitOperation() throws Exception {
        String headBefore = tab().getGitService().getHeadCommitMessage();
        Files.writeString(tempDir.resolve("a.txt"), "changed a", StandardCharsets.UTF_8);
        refreshTab();

        // A bare clickOn("a.txt") matches by text and takes the first hit, which in this
        // class is a ListCell the virtual flow has recycled out of the scene - TestFX
        // then asks that node for its scene and throws on the null. Earlier methods here
        // leave several such cells behind, which is why this test failed in class order
        // and passed alone. Clicking needs the one actually on screen.
        clickOnScreen("a.txt");
        // Selecting the row loads its diff on the git lane; the Edit button is there once
        // it has landed.
        RobotWait.untilOnFx("a.txt's diff never loaded", this::editableFileLoaded);

        Button edit = from(tab().getContent()).lookup(".diff-tool-button").queryAll().stream()
                .map(n -> (Button) n)
                .filter(b -> b.getText().startsWith("Edit"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Edit button found"));
        interact(edit::fire);
        settle();

        DiffTextArea area = (DiffTextArea) from(tab().getContent()).lookup(".code-area").query();
        assertTrue(area.isEditable(), "should be in edit mode");
        String beforeTyping = area.getText();

        interact(() -> area.insertText(0, "TYPED"));
        settle();
        assertTrue(area.getText().startsWith("TYPED"));

        // The editor itself has focus, so Ctrl+Z must undo the typed text, not the
        // commit made in start() - before the fix, the control never even saw the key.
        fireKey(area, KeyCode.Z, true, false);
        settle();

        assertEquals(beforeTyping, area.getText(),
                "Ctrl+Z while editing must undo the typed text");
        assertEquals(headBefore, tab().getGitService().getHeadCommitMessage(),
                "Ctrl+Z while editing must not also undo a git operation");
    }

    private boolean diffViewerShowing() {
        return from(tab().getContent()).lookup(".diff-viewer").query().isVisible();
    }

    /** Opens a.txt and puts it into the editor. */
    private DiffTextArea editA() {
        interact(() -> tab().getRightPanel().openWorkingTreeFile("a.txt"));
        RobotWait.untilOnFx("a.txt never loaded into the viewer", this::editableFileLoaded);
        interact(() -> tab().getRightPanel().editCurrentFile());
        RobotWait.untilOnFx("the editor never opened", () -> tab().isEditingFile());
        return (DiffTextArea) from(tab().getContent()).lookup(".code-area").query();
    }

    /**
     * Esc closes the open file and hands the keyboard back to the history.
     *
     * <p>The Close button's tooltip had promised "(Esc)" with nothing bound to it.
     */
    @Test
    void escapeClosesTheOpenFileAndReturnsToTheHistory() {
        interact(() -> tab().getRightPanel().openWorkingTreeFile("a.txt"));
        RobotWait.untilOnFx("a.txt never loaded into the viewer", this::editableFileLoaded);
        assertTrue(diffViewerShowing());

        fireKey(stage.getScene().getRoot(), KeyCode.ESCAPE, false, false);

        assertFalse(diffViewerShowing(), "Esc should close the file");
        Node focus = stage.getScene().getFocusOwner();
        assertNotNull(focus, "the keyboard must land somewhere after closing");
        Node diffViewer = from(tab().getContent()).lookup(".diff-viewer").query();
        for (Node n = focus; n != null; n = n.getParent()) {
            assertNotSame(diffViewer, n, "not inside the viewer that just closed");
        }
    }

    /**
     * Esc in an editor with unsaved edits asks first, and Cancel leaves everything where
     * it was.
     */
    @Test
    void escapeInADirtyEditorAsksBeforeClosing() {
        DiffTextArea area = editA();
        interact(() -> area.insertText(0, "TYPED"));

        // Not through interact: the question is a nested event loop, and interact would
        // wait for it to close.
        javafx.application.Platform.runLater(() -> Event.fireEvent(area, new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)));
        var dialog = RobotWait.untilDialogWithButton("Discard edits");
        interact(() -> ((Button) dialog.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(tab().isEditingFile(), "Cancel keeps the session");
        assertTrue(diffViewerShowing(), "and the file");
        assertTrue(area.getText().startsWith("TYPED"), "and the typing");
    }

    /**
     * Saving keeps the editor open.
     *
     * <p>A save refreshes the repository; the refresh re-selected the working-tree row and
     * rebuilt the staging panel, and the rebuild closed the viewer - so Ctrl+S ended the
     * session it had just saved.
     */
    @Test
    void savingInTheEditorKeepsItOpenThroughTheRefreshThatFollows() throws Exception {
        DiffTextArea area = editA();
        interact(() -> area.insertText(0, "SAVED "));

        fireKey(area, KeyCode.S, true, false);
        RobotWait.until("the save never reached the disk", () -> {
            try {
                return Files.readString(tempDir.resolve("a.txt")).startsWith("SAVED ");
            } catch (java.io.IOException e) {
                return false;
            }
        });
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(tab().isEditingFile(), "the session should outlive its own save");
        assertTrue(diffViewerShowing(), "and so should the file on screen");
    }

    /**
     * Returning to the window reloads the open diff - and only an open one. The last
     * request outlived closing the viewer, and replaying it reopened the file.
     */
    @Test
    void aReloadAfterClosingDoesNotReopenTheFile() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "changed a", StandardCharsets.UTF_8);
        refreshTab();
        interact(() -> tab().getRightPanel().openWorkingTreeFile("a.txt"));
        RobotWait.untilOnFx("a.txt never loaded into the viewer", this::editableFileLoaded);
        fireKey(stage.getScene().getRoot(), KeyCode.ESCAPE, false, false);
        assertFalse(diffViewerShowing(), "precondition: closed");

        interact(() -> tab().getRightPanel().reloadDiff());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(diffViewerShowing(), "a closed file must stay closed");
    }

    /** A reload of the file being read in File view keeps File view. */
    @Test
    void aReloadKeepsFileView() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "changed a", StandardCharsets.UTF_8);
        refreshTab();
        interact(() -> tab().getRightPanel().openWorkingTreeFile("a.txt"));
        RobotWait.untilOnFx("a.txt never loaded into the viewer", this::editableFileLoaded);
        DiffViewer viewer = from(tab().getContent()).lookup(".diff-viewer").query();
        for (Node node : from(tab().getContent()).lookup(".diff-segment").queryAll()) {
            if (node instanceof javafx.scene.control.ToggleButton toggle
                    && "File view".equals(toggle.getText())) {
                interact(toggle::fire);
            }
        }
        assertTrue(viewer.isFileView(), "precondition: reading in File view");

        interact(() -> tab().getRightPanel().reloadDiff());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(viewer.isFileView(), "the repository changing is not a reason to leave File view");
    }
}
