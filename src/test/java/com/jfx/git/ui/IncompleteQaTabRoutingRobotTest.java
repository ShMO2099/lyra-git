package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.ActivityLog;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.DelegatingGitBackend;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphRow;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U13 in {@code manual-test-artifacts/qa-scenario-improvements.md}: two repositories open in
 * two tabs, a commit started in the first held at a gate while the user moves to the
 * second - or closes the first - and then let go. What is judged is where the outcome
 * lands: the first tab's status bar and activity-log entry, the first repository's history,
 * and nothing of it in the second tab's status bar, graph, staging lists or repository,
 * read through JGit independently of the engine that did the work.
 *
 * <p>Both tabs open through {@link RepoTab#loadRepositoryAsync}, the path the application
 * takes, so each has its own file watcher and its activity is logged under its folder's
 * name. The tab strip is wired as {@code LyraApp} wires its own: selecting a tab asks it
 * whether its repository changed, and its close button runs {@link RepoTab#confirmClose}
 * and then {@link RepoTab#close}.
 *
 * <p>Each repository has a {@link GitTaskRunner} lane of its own, so the second tab's work -
 * the check a tab switch starts, the diff of a file picked in its staging list - runs while
 * the first one's commit is still held, and the first journey asserts that it does. With
 * one lane for every tab it queued behind the held commit, and a slow commit, fetch or
 * status in one tab froze every other.
 *
 * <p>The gate is in the first repository's engine, {@link GatedEngine}, around JGit's
 * commit: the routing under test is the tab's, the same whichever engine sits behind it.
 */
class IncompleteQaTabRoutingRobotTest extends ApplicationTest {

    /** Folder names, and so the names the activity log files each tab's work under. */
    private static final String ALPHA = "u13-alpha";
    private static final String BETA = "u13-beta";

    private static final String ALPHA_BASE = "alpha base\n";
    private static final String ALPHA_EDIT = "alpha edit, staged\n";
    private static final String BETA_BASE = "beta base\n";
    private static final String BETA_EDIT = "beta edit, unstaged\n";
    private static final String BETA_NOTES = "beta notes, untracked\n";
    private static final String COMMIT_MESSAGE = "alpha change";

    @TempDir
    Path root;

    private Path alpha;
    private Path beta;
    private GatedEngine alphaEngine;
    private RepoTab alphaTab;
    private RepoTab betaTab;
    private TabPane pane;
    private boolean originalPushAfterCommit;

    /** Counted down by A's close handler as it starts, and again once {@code close()} returns. */
    private final CountDownLatch alphaCloseStarted = new CountDownLatch(1);
    private final CountDownLatch alphaCloseReturned = new CountDownLatch(1);

    /** Whatever escaped to the FX thread's uncaught-exception handler during the test. */
    private final List<Throwable> fxFailures = new CopyOnWriteArrayList<>();
    private Thread.UncaughtExceptionHandler fxHandlerBefore;

    /** Every text each status bar showed from the moment the commit was started. */
    private final List<String> alphaMessages = new CopyOnWriteArrayList<>();
    private final List<String> betaMessages = new CopyOnWriteArrayList<>();

    /** Activity-log entries that existed before the test began, so they can be left out. */
    private final Set<ActivityLog.Entry> earlierEntries = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public void start(Stage stage) throws Exception {
        alpha = root.resolve(ALPHA);
        beta = root.resolve(BETA);
        try (var fixture = ThreadGuard.buildingFixture()) {
            try (Git git = init(alpha)) {
                write(alpha, "alpha.txt", ALPHA_BASE);
                git.add().addFilepattern("alpha.txt").call();
                git.commit().setMessage("alpha base").call();
                write(alpha, "alpha.txt", ALPHA_EDIT);
                git.add().addFilepattern("alpha.txt").call();
            }
            try (Git git = init(beta)) {
                write(beta, "beta.txt", BETA_BASE);
                git.add().addFilepattern("beta.txt").call();
                git.commit().setMessage("beta base").call();
                write(beta, "beta.txt", BETA_EDIT);
                write(beta, "beta-notes.txt", BETA_NOTES);
            }
        }
        // A commit followed by a push would add a second operation, and a remote, to a
        // journey about where one operation's result lands.
        originalPushAfterCommit = AppPreferences.pushAfterCommit();
        AppPreferences.setPushAfterCommit(false);

        alphaEngine = new GatedEngine();
        LyraApp app = new LyraApp();
        alphaTab = repositoryTab(app, ALPHA, alphaEngine);
        betaTab = repositoryTab(app, BETA, new GitService());
        alphaTab.setOnClosed(e -> {
            alphaCloseStarted.countDown();
            try {
                alphaTab.close();
            } finally {
                alphaCloseReturned.countDown();
            }
        });
        betaTab.setOnClosed(e -> betaTab.close());
        pane = new TabPane(alphaTab, betaTab);
        // LyraApp's own listener: a tab that comes forward asks whether its repository
        // changed while it was in the background.
        pane.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected instanceof RepoTab repoTab) repoTab.refreshIfChangedExternally();
        });
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        alphaTab.loadRepositoryAsync(alpha.toFile(), () -> { }, error -> { });
        betaTab.loadRepositoryAsync(beta.toFile(), () -> { }, error -> { });
    }

    /** A repository tab whose close button asks first, as {@code LyraApp.addRepoTab} builds one. */
    private static RepoTab repositoryTab(LyraApp app, String title, GitBackend engine) {
        RepoTab tab = new RepoTab(app, title, engine);
        tab.setOnCloseRequest(e -> {
            if (!tab.confirmClose()) e.consume();
        });
        return tab;
    }

    @BeforeEach
    void openBothRepositories() {
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the first tab never opened its repository",
                () -> graphCommits(alphaTab).size() == 1 && alphaTab.getStatusBar().busyDepth() == 0);
        RobotWait.untilOnFx("the second tab never opened its repository",
                () -> graphCommits(betaTab).size() == 1 && betaTab.getStatusBar().busyDepth() == 0);
        interact(() -> {
            for (RepoTab tab : List.of(alphaTab, betaTab)) {
                // The uncommitted row, so each tab's right panel is its staging area.
                tab.graphList().getSelectionModel().clearSelection();
                tab.graphList().getSelectionModel().select(0);
            }
        });
        RobotWait.untilOnFx("the first tab never listed its staged file",
                () -> rows(alphaTab, "staging-staged-list").equals(List.of("alpha.txt")));
        RobotWait.untilOnFx("the second tab never listed its own changes",
                () -> rows(betaTab, "staging-unstaged-list").containsAll(List.of("beta.txt", "beta-notes.txt")));
        RobotWait.drainGitWork();
        interact(() -> {
            Thread fx = Thread.currentThread();
            fxHandlerBefore = fx.getUncaughtExceptionHandler();
            fx.setUncaughtExceptionHandler((thread, failure) -> fxFailures.add(failure));
        });
        earlierEntries.addAll(ActivityLog.entries());
    }

    @AfterEach
    void tidyUp() {
        // Whatever happened, the held commit goes free: nothing below may wait on it.
        alphaEngine.release.countDown();
        RobotWait.drainGitWork();
        for (RepoTab tab : List.of(alphaTab, betaTab)) {
            if (tab != null && tab.getTabPane() != null) interact(tab::close);
        }
        if (fxHandlerBefore != null) {
            interact(() -> Thread.currentThread().setUncaughtExceptionHandler(
                    fxHandlerBefore instanceof ThreadGroup ? null : fxHandlerBefore));
        }
        AppPreferences.setPushAfterCommit(originalPushAfterCommit);
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Switching tabs while the commit is held
    // ------------------------------------------------------------------

    @Test
    void aCommitFinishingWhileAnotherTabIsShownReportsInItsOwnTabAndChangesOnlyItsOwnRepository()
            throws Exception {
        String alphaHeadBefore = head(alpha);
        RepoSnapshot betaBefore = RepoSnapshot.of(beta);
        List<String> betaGraphBefore = FxHarness.call(() -> graphCommits(betaTab));

        try {
            startTheCommitInAlpha();
            RobotWait.untilOnFx("the first tab did not say it was committing",
                    () -> "Committing…".equals(statusMessage(alphaTab).getText()));
            interact(() -> pane.getSelectionModel().select(betaTab));
            FxHarness.run(() -> {
                assertFalse(statusMessage(betaTab).getText().contains("Committ"),
                        "the second tab's status bar must not show the first tab's commit");
                assertEquals(List.of(), rows(betaTab, "staging-staged-list"));
                assertTrue(rows(betaTab, "staging-unstaged-list").containsAll(List.of("beta.txt", "beta-notes.txt")));
            });

            // The second tab reads its own repository while the first one's commit is
            // still at the gate: the diff of a file picked in its staging list arrives.
            interact(() -> selectRow(betaTab, "staging-unstaged-list", "beta.txt"));
            // Bounded well inside the gate's own 30 s, so a read queued behind the held
            // commit fails here rather than arriving once the gate gives up.
            RobotWait.untilOnFx("the second tab's diff waited behind the first tab's held commit",
                    () -> "beta.txt".equals(betaTab.diffViewer().filePathProperty().get()),
                    java.time.Duration.ofSeconds(15));
            assertEquals(1, alphaEngine.release.getCount(), "precondition: the commit is still held");
            assertEquals(alphaHeadBefore, head(alpha), "the held commit has not landed yet");
            FxHarness.run(() -> assertEquals("Committing…", statusMessage(alphaTab).getText(),
                    "the first tab is still committing while the second one works"));
            RobotWait.until("the second tab's work waited on the first tab's lane",
                    () -> GitTaskRunner.queued(betaTab.getGitService()) == 0);
        } finally {
            alphaEngine.release.countDown();
        }
        RobotWait.until("the released commit never moved the first repository's HEAD",
                () -> !alphaHeadBefore.equals(head(alpha)));
        String committed = head(alpha);
        String report = "Committed " + committed.substring(0, 7);
        RobotWait.untilOnFx("the first tab never reported its commit", () -> alphaMessages.contains(report));
        RobotWait.drainGitWork();

        // The repositories: the commit is the first one's, and the second is untouched.
        try (Git git = Git.open(alpha.toFile())) {
            RevCommit commit = git.getRepository().parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals(COMMIT_MESSAGE, commit.getFullMessage().strip());
            assertEquals(1, commit.getParentCount());
            assertEquals(alphaHeadBefore, commit.getParent(0).name());
            assertTrue(git.status().call().isClean(), "the staged edit is what was committed");
        }
        assertEquals(ALPHA_EDIT, read(alpha, "alpha.txt"));
        assertEquals(betaBefore, RepoSnapshot.of(beta), "the second repository must not change");

        // The tabs: the result is reported where it was started, and only there.
        assertTrue(betaMessages.stream().noneMatch(m -> m != null && m.contains("Committ")),
                "the second tab's status bar showed the first tab's commit: " + betaMessages);
        assertTrue(alphaMessages.indexOf("Committing…") >= 0
                        && alphaMessages.indexOf("Committing…") < alphaMessages.indexOf(report),
                "the first tab said it was committing, then that it had: " + alphaMessages);
        RobotWait.untilOnFx("the first tab's graph never showed its new commit",
                () -> graphCommits(alphaTab).getFirst().equals(committed));
        FxHarness.run(() -> {
            assertSame(betaTab, pane.getSelectionModel().getSelectedItem(),
                    "a commit finishing in the background must not bring its tab forward");
            assertEquals(betaGraphBefore, graphCommits(betaTab), "the second tab's graph is still its own");
            assertEquals(List.of(), rows(betaTab, "staging-staged-list"));
            assertEquals(List.of("beta-notes.txt", "beta.txt"),
                    rows(betaTab, "staging-unstaged-list").stream().sorted().toList());
            assertEquals(List.of(), rows(alphaTab, "staging-staged-list"), "the first tab's index is committed");
        });

        // The activity log files the commit under the repository it happened in.
        List<ActivityLog.Entry> commits = newEntries().stream()
                .filter(e -> "Committing".equals(e.label())).toList();
        assertEquals(1, commits.size(), "one commit was made: " + describe(commits));
        assertEquals(ALPHA, commits.getFirst().repository());
        assertEquals(ActivityLog.Outcome.OK, commits.getFirst().outcome());
        assertEquals(report, commits.getFirst().detail());
        assertTrue(fxFailures.isEmpty(), "nothing may escape on the FX thread: " + fxFailures);
    }

    // ------------------------------------------------------------------
    // Closing the tab while the commit is held
    // ------------------------------------------------------------------

    @Test
    void closingATabWhileItsCommitIsHeldLetsTheCommitFinishAndLeavesTheOtherTabWorking() throws Exception {
        String alphaHeadBefore = head(alpha);
        RepoSnapshot betaBefore = RepoSnapshot.of(beta);
        assertTrue(watcherRunning(ALPHA), "the first tab watches its repository while it is open");

        try {
            startTheCommitInAlpha();
            // Not interact: close() waits on the FX thread for the held lane.
            Node closeButton = FxHarness.call(() -> closeButtonOf(alphaTab));
            Platform.runLater(() -> press(closeButton));
            assertTrue(alphaCloseStarted.await(10, TimeUnit.SECONDS), "the close button never closed the tab");
        } finally {
            alphaEngine.release.countDown();
        }
        assertTrue(alphaCloseReturned.await(15, TimeUnit.SECONDS), "closing the tab never returned");
        RobotWait.drainGitWork();

        assertTrue(fxFailures.isEmpty(), "nothing may escape on the FX thread: " + fxFailures);
        // The commit was let finish, and only then was the repository released.
        assertTrue(alphaEngine.closedAfterCommit, "the engine was released under the running commit");
        try (Git git = Git.open(alpha.toFile())) {
            RevCommit commit = git.getRepository().parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals(COMMIT_MESSAGE, commit.getFullMessage().strip());
            assertEquals(alphaHeadBefore, commit.getParent(0).name());
            assertTrue(git.status().call().isClean(), "the commit landed whole");
        }
        RepoSnapshot alphaAfterClose = RepoSnapshot.of(alpha);

        FxHarness.run(() -> {
            assertEquals(List.of(betaTab), List.copyOf(pane.getTabs()));
            assertSame(betaTab, pane.getSelectionModel().getSelectedItem());
        });
        assertEquals(betaBefore, RepoSnapshot.of(beta), "the second repository must not change");
        assertTrue(betaMessages.stream().noneMatch(m -> m != null && m.contains("Committ")),
                "the second tab's status bar showed the closed tab's commit: " + betaMessages);
        RobotWait.until("the closed tab's file watcher is still running", () -> !watcherRunning(ALPHA));
        assertTrue(watcherRunning(BETA), "the open tab keeps watching its own repository");

        // The tab left behind still works, on its own repository.
        FxHarness.run(() -> button(betaTab, "Stage all").fire());
        RobotWait.until("the second tab's Stage all never staged its repository", () -> stagedIn(beta)
                .equals(List.of("beta-notes.txt", "beta.txt")));
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the second tab never listed what it staged", () ->
                rows(betaTab, "staging-staged-list").containsAll(List.of("beta.txt", "beta-notes.txt")));
        assertEquals(BETA_EDIT, read(beta, "beta.txt"));
        assertEquals(alphaAfterClose, RepoSnapshot.of(alpha), "work in the open tab reaches only its repository");
        assertEquals(0, GitTaskRunner.queued(), "nothing is left waiting on any repository's lane");

        // The commit is on record under its own repository, as having worked.
        List<ActivityLog.Entry> logged = newEntries();
        List<ActivityLog.Entry> commits = logged.stream().filter(e -> "Committing".equals(e.label())).toList();
        assertEquals(1, commits.size(), describe(logged));
        assertEquals(ALPHA, commits.getFirst().repository());
        assertEquals(ActivityLog.Outcome.OK, commits.getFirst().outcome(), describe(commits));
        // Closing is the user's last word on a repository: what the closed tab did after it
        // - a refresh of history it no longer shows, against an engine it had released -
        // is work nobody asked for, and its failures would be filed under that repository.
        assertEquals(List.of(), alphaEngine.callsAfterClose,
                "the closed tab ran git work against its released repository");
        assertTrue(logged.stream().noneMatch(e -> ALPHA.equals(e.repository())
                        && e.outcome() == ActivityLog.Outcome.FAILED),
                "the closed tab logged failures after it closed: " + describe(logged));
    }

    // ------------------------------------------------------------------
    // The journey's steps
    // ------------------------------------------------------------------

    /**
     * Types the message in the first tab's composer and presses its commit button, then
     * waits for the commit to reach the gate. Both status bars are recorded from here on.
     */
    private void startTheCommitInAlpha() throws InterruptedException {
        interact(() -> {
            record(alphaTab, alphaMessages);
            record(betaTab, betaMessages);
            TextInputControl summary = FxHarness.lookupAll(alphaTab.getContent(), "composer-summary").stream()
                    .filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast)
                    .filter(field -> !field.getStyleClass().contains("stash-title"))
                    .findFirst().orElseThrow(() -> new AssertionError("no commit composer in the first tab"));
            summary.setText(COMMIT_MESSAGE);
            button(alphaTab, "Commit 1 file to main").fire();
        });
        assertTrue(alphaEngine.entered.await(15, TimeUnit.SECONDS), "the commit never reached the gate");
    }

    /** Presses a tab's close button the way a mouse does; see {@code ToolTabsTest}. */
    private static void press(Node closeButton) {
        Event.fireEvent(closeButton, new MouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
        Event.fireEvent(closeButton, new MouseEvent(MouseEvent.MOUSE_RELEASED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, false, null));
    }

    /** The close button in the header showing {@code tab}'s title. Call on the FX thread. */
    private Node closeButtonOf(Tab tab) {
        pane.applyCss();
        pane.layout();
        for (Node button : pane.lookupAll(".tab-close-button")) {
            for (Node up = button.getParent(); up != null; up = up.getParent()) {
                if (up.getStyleClass().contains("tab")) {
                    if (up.lookup(".tab-label") instanceof Label label && tab.getText().equals(label.getText())) {
                        return button;
                    }
                    break;
                }
            }
        }
        throw new AssertionError("no close button for " + tab.getText());
    }

    // ------------------------------------------------------------------
    // What each tab shows
    // ------------------------------------------------------------------

    private static Label statusMessage(RepoTab tab) {
        return (Label) FxHarness.lookupAll(tab.getStatusBar(), "status-message").getFirst();
    }

    private static void record(RepoTab tab, List<String> into) {
        Label label = statusMessage(tab);
        into.add(label.getText());
        label.textProperty().addListener((obs, was, now) -> into.add(now));
    }

    private static Button button(RepoTab tab, String text) {
        return FxHarness.lookupAll(tab.getContent(), "button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> text.equals(b.getText()) && !b.isDisabled())
                .findFirst().orElseThrow(() -> new AssertionError("no \"" + text + "\" button in " + tab.getText()));
    }

    /** Selects {@code item} in the list with {@code style}, as a click on its row does. Call on the FX thread. */
    private static void selectRow(RepoTab tab, String style, String item) {
        ListView<?> list = FxHarness.lookupAll(tab.getContent(), style).stream()
                .filter(ListView.class::isInstance).map(node -> (ListView<?>) node)
                .findFirst().orElseThrow(() -> new AssertionError("no " + style + " in " + tab.getText()));
        int index = list.getItems().stream().map(String::valueOf).toList().indexOf(item);
        if (index < 0) throw new AssertionError(item + " is not listed in " + tab.getText() + "'s " + style);
        list.getSelectionModel().clearSelection();
        list.getSelectionModel().select(index);
    }

    private static List<String> rows(RepoTab tab, String style) {
        return FxHarness.lookupAll(tab.getContent(), style).stream()
                .filter(ListView.class::isInstance)
                .map(node -> ((ListView<?>) node).getItems().stream().map(String::valueOf).toList())
                .findFirst().orElse(List.of());
    }

    /** The commits a tab's graph shows, newest first, without the uncommitted row. */
    private static List<String> graphCommits(RepoTab tab) {
        return tab.graphList().getItems().stream()
                .filter(row -> !row.isWipNode() && !row.isLoadMore() && !row.isStash() && row.getCommit() != null)
                .map(GitGraphRow::getCommit).map(c -> c.getHash()).toList();
    }

    private static boolean watcherRunning(String folder) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && ("repo-watch-" + folder).equals(t.getName()));
    }

    private List<ActivityLog.Entry> newEntries() {
        return ActivityLog.entries().stream().filter(e -> !earlierEntries.contains(e)).toList();
    }

    private static String describe(List<ActivityLog.Entry> entries) {
        return entries.stream().map(e -> e.repository() + ": " + e.label() + " " + e.outcome()
                + (e.detail() == null ? "" : " (" + e.detail() + ")")).toList().toString();
    }

    // ------------------------------------------------------------------
    // The repositories, read through JGit
    // ------------------------------------------------------------------

    private static String head(Path workTree) {
        try (Git git = Git.open(workTree.toFile())) {
            return git.getRepository().resolve("HEAD").name();
        } catch (Exception e) {
            throw new AssertionError("could not read HEAD of " + workTree, e);
        }
    }

    private static List<String> stagedIn(Path workTree) {
        try (Git git = Git.open(workTree.toFile())) {
            var status = git.status().call();
            List<String> staged = new ArrayList<>(status.getAdded());
            staged.addAll(status.getChanged());
            Collections.sort(staged);
            return staged;
        } catch (Exception e) {
            throw new AssertionError("could not read the index of " + workTree, e);
        }
    }

    private static String read(Path workTree, String file) throws Exception {
        return Files.readString(workTree.resolve(file), StandardCharsets.UTF_8);
    }

    private static void write(Path workTree, String file, String content) throws Exception {
        Files.writeString(workTree.resolve(file), content, StandardCharsets.UTF_8);
    }

    private static Git init(Path workTree) throws Exception {
        Git git = Git.init().setDirectory(workTree.toFile()).setInitialBranch("main").call();
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Tab Routing QA");
        config.setString("user", null, "email", "tab-routing@example.test");
        config.setBoolean("core", null, "autocrlf", false);
        config.setBoolean("commit", null, "gpgsign", false);
        config.save();
        return git;
    }

    // ------------------------------------------------------------------
    // The first repository's engine
    // ------------------------------------------------------------------

    /**
     * JGit, with its commit held at a gate: {@link #entered} counts down when the commit
     * arrives, and it waits for {@link #release} - bounded, so a failed test cannot hang the
     * run. Every call that reaches the engine after {@link #close} is recorded, named by the
     * engine method and the application code that asked.
     */
    private static final class GatedEngine extends DelegatingGitBackend {
        private final GitService jgit = new GitService();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> callsAfterClose = new CopyOnWriteArrayList<>();
        private volatile boolean closed;
        private volatile boolean commitReturned;
        volatile boolean closedAfterCommit;

        @Override protected GitBackend reader() { return engine(); }
        @Override protected GitBackend writer() { return engine(); }

        private GitBackend engine() {
            if (closed) callsAfterClose.add(caller());
            return jgit;
        }

        @Override
        public GitResult commit(String message, boolean amend, boolean skipHooks) {
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    return GitResult.failed("The test never released the held commit");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return GitResult.failed("Interrupted at the gate");
            }
            try {
                return super.commit(message, amend, skipHooks);
            } finally {
                commitReturned = true;
            }
        }

        @Override public boolean openRepository(File dir) { return jgit.openRepository(dir); }
        @Override public boolean isOpen() { return jgit.isOpen(); }
        @Override public File getDirectory() { return jgit.getDirectory(); }
        @Override public File getWorkTree() { return jgit.getWorkTree(); }

        @Override
        public void close() {
            closedAfterCommit = commitReturned;
            closed = true;
            jgit.close();
        }

        /** "engine method <- the first application frame that led to it". */
        private static String caller() {
            return StackWalker.getInstance().walk(frames -> {
                List<StackWalker.StackFrame> list = frames.toList();
                String method = list.stream()
                        .filter(f -> f.getClassName().equals(DelegatingGitBackend.class.getName()))
                        .map(StackWalker.StackFrame::getMethodName).findFirst().orElse("?");
                String from = list.stream()
                        .filter(f -> f.getClassName().startsWith("com.jfx.git.ui.")
                                && !f.getClassName().startsWith(IncompleteQaTabRoutingRobotTest.class.getName()))
                        .map(f -> f.getClassName().substring("com.jfx.git.ui.".length()) + "."
                                + f.getMethodName() + ":" + f.getLineNumber())
                        .findFirst().orElse("?");
                return method + " <- " + from;
            });
        }
    }
}
