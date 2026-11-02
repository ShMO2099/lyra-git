package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.core.Settings;
import com.jfx.git.core.TabSession;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every app-wide "stage all", in the middle of a merge stopped on two conflicts.
 *
 * <p>Staging a conflicted file is how git is told it is resolved. {@code Ctrl+Shift+S},
 * the palette's "Git: Stage all" and the Staged section's empty-state button all ran
 * {@code git add -A}, which marked both conflicts resolved with their {@code <<<<<<<}
 * markers in them and left "Commit merge" enabled; {@code Ctrl+Alt+Enter} went on to
 * commit them, a merge with markers in two files from one keystroke. Each is driven here
 * the way the user reaches it - the shortcuts through the real window's keyboard map, as
 * {@link AppShortcutsRobotTest} does - and the repository is asked afterwards, through
 * JGit and not the application, whether the conflicts are still unmerged.
 *
 * <p>The last tests are the defence behind them: markers staged on purpose, past the
 * warning, are named again before the merge commit - from the composer with every check
 * turned off, and from the banner's Commit merge, which is git's own continue.
 */
class ConflictStageAllRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    /** The repository: a folder of its own, so the settings file beside it is not a change in it. */
    private Path repository;
    private LyraApp app;
    private Stage stage;
    private String headBefore;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        repository = Files.createDirectories(tempDir.resolve("repo"));
        Settings.useFile(tempDir.resolve("settings.properties"));
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        // Both branches rewrite the same line of both files: a conflict no engine can merge.
        GitService service = new GitService();
        try {
            service.openRepository(repository.toFile());
            commit(service, "base", "a.txt", "base a\n", "b.txt", "base b\n");
            String main = service.getCurrentBranch();
            assertTrue(service.createBranch("feature/simple", null, true).isOk());
            commit(service, "feature: rewrite both", "a.txt", "feature a\n", "b.txt", "feature b\n");
            assertTrue(service.checkoutBranch(main).isOk());
            commit(service, "main: rewrite both too", "a.txt", "main a\n", "b.txt", "main b\n");
            assertTrue(new RepositoryCommands(service).merge("feature/simple", false).isConflict());
            headBefore = service.getHeadHash();
        } finally {
            service.close();
        }
        assertEquals(Set.of("a.txt", "b.txt"), unmerged(), "the fixture should stop on both files");

        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", repository.toAbsolutePath().toString());
        app = new LyraApp();
        app.start(stage);
    }

    @BeforeEach
    void openTheRepositoryTab() {
        RobotWait.untilOnFx("repo.last should have opened the repository in a tab",
                () -> app.getActiveRepoTab() != null && app.getActiveRepoTab().getGitService().isOpen());
        RobotWait.drainGitWork();
    }

    @AfterEach
    void tidyUp() {
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
        TabSession.setOpenPaths(List.of(), 0);
        Settings.useFile(null);
    }

    private void commit(GitService service, String message, String... pathsAndContents) throws Exception {
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            Files.writeString(repository.resolve(pathsAndContents[i]), pathsAndContents[i + 1], StandardCharsets.UTF_8);
            assertTrue(service.stageFile(pathsAndContents[i]).isOk());
        }
        assertTrue(service.commit(message, false).isOk());
    }

    // ------------------------------------------------------------------
    // The repository, read without the application
    // ------------------------------------------------------------------

    /** The paths git lists as unmerged - {@code git ls-files -u}, by JGit. */
    private Set<String> unmerged() {
        try (Git git = Git.open(repository.toFile())) {
            return git.status().call().getConflicting();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<String> staged() {
        try (Git git = Git.open(repository.toFile())) {
            var status = git.status().call();
            var staged = new java.util.TreeSet<String>();
            staged.addAll(status.getAdded());
            staged.addAll(status.getChanged());
            return staged;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String head() {
        try (Git git = Git.open(repository.toFile())) {
            return git.getRepository().resolve("HEAD").name();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Neither conflict resolved, both files still as the merge left them, and nothing committed. */
    private void assertConflictsUntouched(String gesture) throws Exception {
        RobotWait.drainGitWork();
        assertEquals(Set.of("a.txt", "b.txt"), unmerged(),
                gesture + " must leave both conflicts unmerged, not mark them resolved");
        assertEquals(headBefore, head(), gesture + " must not commit anything");
        assertTrue(Files.readString(repository.resolve("a.txt")).contains("<<<<<<< "),
                "a.txt should still hold the conflict for the merge editor");
    }

    // ------------------------------------------------------------------
    // The application
    // ------------------------------------------------------------------

    private RepoTab tab() {
        return app.getActiveRepoTab();
    }

    /** A file that is not part of the merge: what Stage all should still stage. */
    private void addPlainChange() throws Exception {
        Files.writeString(repository.resolve("notes.txt"), "a note\n", StandardCharsets.UTF_8);
        interact(() -> tab().refresh());
        RobotWait.drainGitWork();
    }

    /** The uncommitted changes on screen, with both conflicts listed. */
    private void openWorkingTree() {
        RobotWait.untilOnFx("the stopped merge never produced a WIP row",
                () -> !tab().graphList().getItems().isEmpty() && tab().graphList().getItems().get(0).isWipNode());
        interact(() -> {
            tab().graphList().getSelectionModel().clearSelection();
            tab().graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("the Conflicted files section never listed both files",
                () -> rows("staging-conflicted-list").equals(List.of("a.txt", "b.txt")));
    }

    /** The items of the list carrying {@code styleClass}. Call on FX. */
    private List<String> rows(String styleClass) {
        return FxHarness.lookupAll(tab().getContent(), styleClass).stream()
                .filter(node -> node instanceof ListView<?>)
                .<List<String>>map(node -> ((ListView<?>) node).getItems().stream().map(String::valueOf).toList())
                .findFirst().orElse(List.of());
    }

    /** The Staged section's empty-state Stage all, or null while it is not in the scene. Call on FX. */
    private Button emptyStateStageAll() {
        return FxHarness.lookupAll(tab().getContent(), "staged-empty-action").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getScene() != null)
                .findFirst().orElse(null);
    }

    /** The status bar's message, as the user reads it. Call on FX. */
    private String statusMessage() {
        return FxHarness.lookupAll(tab().getStatusBar(), "status-message").stream()
                .filter(Label.class::isInstance).map(node -> ((Label) node).getText())
                .findFirst().orElse("");
    }

    /** A real KEY_PRESSED on the main scene, where the window's keyboard map filters. */
    private void fireAppShortcut(KeyCode code, boolean shift, boolean alt) {
        interact(() -> Event.fireEvent(stage.getScene().getRoot(), new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", code, shift, true, alt, false)));
    }

    private void answer(DialogPane pane, String label) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream().filter(t -> label.equals(t.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("missing dialog button " + label));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    // ------------------------------------------------------------------

    @Test
    void ctrlShiftSStagesTheRestAndLeavesTheConflicts() throws Exception {
        addPlainChange();

        fireAppShortcut(KeyCode.S, true, false);

        RobotWait.until("Ctrl+Shift+S never staged notes.txt", () -> staged().contains("notes.txt"));
        assertConflictsUntouched("Ctrl+Shift+S");
        RobotWait.untilOnFx("the status bar should say the conflicts are still there",
                () -> statusMessage().equals("Staged 1 file - 2 conflicts left"));
    }

    @Test
    void ctrlShiftSWithOnlyConflictsStagesNothing() throws Exception {
        fireAppShortcut(KeyCode.S, true, false);

        RobotWait.untilOnFx("the status bar should say nothing was staged",
                () -> statusMessage().equals("Nothing staged - 2 conflicts left"));
        assertConflictsUntouched("Ctrl+Shift+S");
    }

    @Test
    void thePalettesStageAllLeavesTheConflicts() throws Exception {
        addPlainChange();

        fireAppShortcut(KeyCode.P, false, false);
        Node search = lookup(".palette-search").query();
        clickOn(search);
        write("Stage all");
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> Event.fireEvent(search, new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));

        RobotWait.until("Git: Stage all never staged notes.txt", () -> staged().contains("notes.txt"));
        assertConflictsUntouched("The palette's Git: Stage all");
    }

    @Test
    void theEmptyStagedSectionOffersStageAllOnlyForWhatIsNotAConflict() throws Exception {
        openWorkingTree();
        // Nothing merged cleanly, so the Staged section is empty - and everything it could
        // stage is a conflict. Offered here, right under them, it read as the way to finish.
        RobotWait.untilOnFx("the Staged section should be empty", () -> rows("staging-staged-list").isEmpty());
        assertTrue(FxHarness.call(() -> emptyStateStageAll() == null || !emptyStateStageAll().isVisible()),
                "with only conflicts left there is nothing for the empty Staged section's Stage all to stage");

        addPlainChange();
        // The window's file watcher and focus checks are what bring a new file into the
        // panel in use; here the panel is asked to read the working tree again.
        interact(() -> tab().getRightPanel().showStagingArea());
        RobotWait.untilOnFx("notes.txt never reached the Unstaged list",
                () -> rows("staging-unstaged-list").equals(List.of("notes.txt")));
        RobotWait.untilOnFx("a plain change should bring the empty-state Stage all back",
                () -> emptyStateStageAll() != null && emptyStateStageAll().isVisible());
        interact(() -> emptyStateStageAll().fire());

        RobotWait.until("the empty-state Stage all never staged notes.txt", () -> staged().contains("notes.txt"));
        assertConflictsUntouched("The empty Staged section's Stage all");
        RobotWait.untilOnFx("the panel should still list both conflicts",
                () -> rows("staging-conflicted-list").equals(List.of("a.txt", "b.txt")));
    }

    @Test
    void ctrlAltEnterRefusesWhileConflictsRemain() throws Exception {
        addPlainChange();
        openWorkingTree();
        interact(() -> ((TextInputControl) lookup(".composer-summary").query()).setText("Merge feature/simple"));

        fireAppShortcut(KeyCode.ENTER, false, true);

        RobotWait.untilOnFx("Ctrl+Alt+Enter should say what the commit button says",
                () -> statusMessage().contains("Resolve 2 conflicts first"));
        assertConflictsUntouched("Ctrl+Alt+Enter");
        assertFalse(staged().contains("notes.txt"), "a refused Stage all and commit stages nothing either");
    }

    /** Mark all resolved, past its warning when a file still holds markers ("Mark resolved anyway" is its OK). */
    private void markAllResolved(boolean expectWarning) {
        Platform.runLater(() -> FxHarness.lookupAll(tab().getContent(), "mark-all-resolved").stream()
                .filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow().fire());
        if (expectWarning) {
            DialogPane ask = RobotWait.untilDialogWithButton("OK");
            interact(() -> ((Button) ask.lookupButton(ButtonType.OK)).fire());
        }
        RobotWait.until("Mark all resolved never staged the conflicts", () -> unmerged().isEmpty());
    }

    /** The stopped-operation banner's button with {@code text}, once it is enabled. */
    private Button enabledBannerButton(String text) {
        Button[] found = new Button[1];
        RobotWait.untilOnFx("the banner never offered an enabled " + text, () -> {
            found[0] = FxHarness.lookupAll(tab().getContent(), "operation-banner").stream()
                    .flatMap(banner -> FxHarness.lookupAll(banner, "button").stream())
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> text.equals(button.getText()) && !button.isDisabled())
                    .findFirst().orElse(null);
            return found[0] != null;
        });
        return found[0];
    }

    /** The findings dialog, checked to name the markers and to offer no Unstage for them. */
    private DialogPane markerFindings() {
        DialogPane findings = RobotWait.untilDialogWithButton("Commit anyway");
        assertTrue(FxHarness.call(() -> FxHarness.lookupAll(findings, "label").stream()
                        .filter(Label.class::isInstance).map(node -> ((Label) node).getText())
                        .anyMatch(text -> text.startsWith("Still has conflict markers"))),
                "the findings should name the markers");
        assertTrue(FxHarness.call(() -> FxHarness.lookupAll(findings, "commit-checks-unstage").isEmpty()),
                "a file with markers is not offered Unstage: mid-merge that silently takes HEAD's side");
        return findings;
    }

    @Test
    void markersStagedPastTheWarningAreNamedAgainBeforeTheMergeCommit() throws Exception {
        // Every check the Commit settings offer turned off: a merge commit is looked over
        // for markers regardless.
        AppPreferences.setCheckSecretsBeforeCommit(false);
        AppPreferences.setCheckLargeFilesBeforeCommit(false);
        openWorkingTree();
        markAllResolved(true);
        // Until the panel has read the resolution, the box still says "Resolve 2 conflicts
        // first", and the shortcut is refused as the button would be.
        RobotWait.untilOnFx("the composer never offered to commit the merge",
                () -> FxHarness.lookupAll(tab().getContent(), "btn-primary").stream()
                        .anyMatch(n -> n instanceof Button b && b.getText() != null
                                && b.getText().startsWith("Commit merge") && !b.isDisabled()));

        interact(() -> ((TextInputControl) lookup(".composer-summary").query()).setText("Merge feature/simple"));
        fireAppShortcut(KeyCode.ENTER, false, false);

        answer(markerFindings(), "Cancel");
        RobotWait.drainGitWork();
        assertEquals(headBefore, head(), "Cancel must leave the merge uncommitted");
    }

    /**
     * The banner's Commit merge is git's own {@code merge --continue}, which never went
     * through the composer's checks: markers marked resolved went straight into the merge.
     */
    @Test
    void theBannersCommitMergeNamesTheMarkersFirst() throws Exception {
        openWorkingTree();
        markAllResolved(true);

        Button commitMerge = enabledBannerButton("Commit merge");
        interact(commitMerge::fire);

        answer(markerFindings(), "Cancel");
        RobotWait.drainGitWork();
        assertEquals(headBefore, head(), "Cancel must leave the merge uncommitted");
        assertTrue(Files.exists(repository.resolve(".git/MERGE_HEAD")), "and the merge still in progress");
    }

    /** A clean resolution goes through the banner as it always did, with nothing asked. */
    @Test
    void theBannersCommitMergeCommitsACleanResolution() throws Exception {
        openWorkingTree();
        Files.writeString(repository.resolve("a.txt"), "merged a\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("b.txt"), "merged b\n", StandardCharsets.UTF_8);
        markAllResolved(false);

        Button commitMerge = enabledBannerButton("Commit merge");
        interact(commitMerge::fire);

        RobotWait.until("the merge was never committed", () -> !head().equals(headBefore));
        try (Git git = Git.open(repository.toFile())) {
            var merge = git.getRepository().parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals(2, merge.getParentCount(), "a merge commit, finishing the merge");
        }
        assertFalse(Files.exists(repository.resolve(".git/MERGE_HEAD")));
    }
}
