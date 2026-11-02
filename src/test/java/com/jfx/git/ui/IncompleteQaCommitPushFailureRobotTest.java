package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.CommitMessages;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Push failure after a successful local commit" in {@code
 * manual-test-artifacts/qa-scenario-improvements.md}: committing and pushing from the commit
 * box while the remote refuses the push, then retrying with the toolbar's ordinary Push once
 * the refusal is gone. Judged by what the user is told, and by the repositories afterwards -
 * the commit, its parent and tree, HEAD's reflog, the local and remote refs - read through
 * JGit directly rather than through the engine that did the work.
 *
 * <p>The fixture is a local bare {@code origin} and a work repository whose {@code main} was
 * pushed there and tracks it. {@code readme.txt} is edited and {@code feature.txt} added, both
 * staged; an edit to {@code notes.txt} is left unstaged, so a commit of anything but the index
 * would show in its tree.
 *
 * <h2>How the push is made to fail</h2>
 * A stale {@code refs/heads/main.lock} in the bare remote. The receiving end of both engines -
 * git's {@code receive-pack} for the CLI, and for JGit its own {@code ReceivePack}, run
 * in-process for a local path - moves a ref by creating that lock file exclusively, so both
 * refuse the update once the objects have arrived, which is where a server's hook would
 * refuse it; deleting the file lifts the refusal. Not a {@code pre-receive} hook: JGit runs no
 * hook scripts on the receiving side, so it would refuse on the CLI only. Not renaming the
 * bare directory away: on Windows a rename fails while anything still holds a file inside it
 * open, and putting it back is then the step that flakes.
 *
 * <p>Two routes reach the same commit-then-push: {@code Ctrl+Shift+Enter} in the message field,
 * which commits and pushes whatever the Commit settings say, and the commit button once
 * "Push after commit" is on, when it reads "Commit and push to main". The window-level
 * {@code Ctrl+Shift+Enter} in {@code LyraApp} calls {@link RightPanelManager#commitAndPush()},
 * which is the same {@code commitStaged(true)}.
 *
 * <p>JGit is the engine here; {@link IncompleteQaCommitPushFailureNativeRobotTest} runs the
 * same journeys on the git CLI, which writes for the default Hybrid engine.
 */
class IncompleteQaCommitPushFailureRobotTest extends ApplicationTest {

    private static final String README = "first line\n";
    private static final String README_EDIT = "first line\nsecond line\n";
    private static final String NOTES = "notes\n";
    private static final String NOTES_EDIT = "notes, edited but never staged\n";
    private static final String FEATURE = "a new file\n";
    private static final String MESSAGE = "Add the feature";

    /** What the commit must hold: the index, never the unstaged edit to notes.txt. */
    private static final Map<String, String> COMMITTED_TREE = Map.of(
            "feature.txt", FEATURE, "notes.txt", NOTES, "readme.txt", README_EDIT);

    @TempDir
    Path root;

    private Path work;
    private Path origin;
    private GitBackend backend;
    private RepoTab tab;
    private boolean originalPushAfterCommit;

    /** The engine under test, opened on {@code workTree}; the CLI subclass swaps it. */
    protected GitBackend openEngine(Path workTree) throws Exception {
        GitService service = new GitService();
        assertTrue(service.openRepository(workTree.toFile()));
        return service;
    }

    @Override
    public void start(Stage stage) throws Exception {
        origin = root.resolve("origin.git");
        work = root.resolve("work");
        // Committing remembers the message, and typing saves a draft; neither may reach the
        // user's own message store.
        CommitMessages.useFile(root.resolve("commit-messages.json"));
        try (var fixture = ThreadGuard.buildingFixture()) {
            Git.init().setBare(true).setDirectory(origin.toFile()).setInitialBranch("main").call().close();
            try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Synthetic QA");
                config.setString("user", null, "email", "qa@example.test");
                config.setBoolean("core", null, "autocrlf", false);
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
                write("readme.txt", README);
                write("notes.txt", NOTES);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("base").call();
                git.remoteAdd().setName("origin").setUri(new URIish(origin.toUri().toString())).call();
                git.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
                git.fetch().setRemote("origin").call();
                config.setString("branch", "main", "remote", "origin");
                config.setString("branch", "main", "merge", "refs/heads/main");
                config.save();

                write("readme.txt", README_EDIT);
                write("feature.txt", FEATURE);
                git.add().addFilepattern("readme.txt").addFilepattern("feature.txt").call();
                write("notes.txt", NOTES_EDIT);
            }
            backend = openEngine(work);
        }
        originalPushAfterCommit = AppPreferences.pushAfterCommit();

        tab = new RepoTab(new LyraApp(), "commit and push", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 760));
        stage.show();
    }

    @BeforeEach
    void loadTheRepository() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
    }

    @AfterEach
    void tidyUp() throws Exception {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        interact(() -> {
            AppPreferences.setPushAfterCommit(originalPushAfterCommit);
            // Static FX state, reset on the FX thread; see RightPanelManager.COMMIT_SETTINGS.
            RightPanelManager.commitSettingsChanged();
            new ArrayList<>(Window.getWindows()).forEach(Window::hide);
        });
        Files.deleteIfExists(mainLock());
        CommitMessages.useFile(null);
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // The push fails after the commit
    // ------------------------------------------------------------------

    @Test
    void ctrlShiftEnterCommitsOnceAndSaysTheCommitWasMadeWhenTheRemoteRefusesThePush() throws Exception {
        String oldTip = resolve(work, "refs/heads/main");
        assertEquals(oldTip, resolve(origin, "refs/heads/main"), "the fixture starts with main pushed");
        List<String> reflogBefore = RepoSnapshot.of(work).headReflog();
        Map<String, String> originBefore = refs(origin);
        Map<String, String> upstreamBefore = upstreamOfMain();
        refuseUpdatesToMainOnTheRemote();

        openStagingArea("Commit 2 files to main");
        interact(() -> summary().setText(MESSAGE));
        pressInSummary(KeyCode.ENTER, true, true);

        RobotWait.until("the commit never landed", () -> !oldTip.equals(resolve(work, "refs/heads/main")));
        RobotWait.untilOnFx("the refused push was never reported",
                () -> statusMessage().getStyleClass().contains("status-error"));
        RobotWait.drainGitWork();

        // Committed exactly once, on the old tip, holding exactly what was staged.
        String committed = resolve(work, "refs/heads/main");
        assertOneCommitOnTopOf(oldTip, committed);
        List<String> reflog = RepoSnapshot.of(work).headReflog();
        assertEquals(reflogBefore.size() + 1, reflog.size(), "HEAD moved exactly once: " + reflog);
        assertTrue(reflog.getFirst().startsWith(oldTip + " " + committed + " "),
                "and that move was the commit: " + reflog.getFirst());
        assertEquals(NOTES_EDIT, read("notes.txt"), "the unstaged edit is still on disk");
        Status status = status();
        assertEquals(Set.of("notes.txt"), status.getModified(), "and still unstaged");
        assertTrue(status.getAdded().isEmpty() && status.getChanged().isEmpty(),
                "everything staged went into the commit: " + status.getAdded() + status.getChanged());

        // The remote refused it, and this clone still knows the remote does not have it.
        assertEquals(originBefore, refs(origin), "the refused push moved nothing on the remote");
        assertEquals(oldTip, resolve(work, "refs/remotes/origin/main"),
                "origin/main still records what the remote has");
        assertEquals(upstreamBefore, upstreamOfMain(), "the branch's upstream is untouched");

        // What the user is told: the commit was made, and only the push failed.
        String shown = onFx(() -> statusMessage().getText());
        assertTrue(saysCommittedButNotPushed(shown),
                "the status bar must say the commit was made and the push failed: " + shown);
        List<String> errors = onFx(this::errorToastTexts);
        assertTrue(errors.stream().anyMatch(IncompleteQaCommitPushFailureRobotTest::saysCommittedButNotPushed),
                "and so must the error notification: " + errors);

        // Nothing is left to commit a second time by pressing again.
        interact(() -> {
            assertEquals("", summary().getText(), "the committed message left the composer");
            assertEquals("Nothing staged", commitButton().getText());
            assertTrue(commitButton().isDisabled(), "there is nothing to commit twice");
        });
    }

    // ------------------------------------------------------------------
    // Retrying with the ordinary Push
    // ------------------------------------------------------------------

    @Test
    void retryingWithTheToolbarPushPublishesThatSameCommitWithoutAnotherCommitOrAForce() throws Exception {
        interact(() -> {
            AppPreferences.setPushAfterCommit(true);
            RightPanelManager.commitSettingsChanged();
        });
        String oldTip = resolve(work, "refs/heads/main");
        Map<String, String> upstreamBefore = upstreamOfMain();
        refuseUpdatesToMainOnTheRemote();

        openStagingArea("Commit and push to main");
        interact(() -> summary().setText(MESSAGE));
        interact(() -> commitButton().fire());
        RobotWait.until("the commit never landed", () -> !oldTip.equals(resolve(work, "refs/heads/main")));
        RobotWait.untilOnFx("the refused push was never reported",
                () -> statusMessage().getStyleClass().contains("status-error"));
        RobotWait.drainGitWork();
        String committed = resolve(work, "refs/heads/main");
        assertOneCommitOnTopOf(oldTip, committed);
        assertEquals(oldTip, resolve(origin, "refs/heads/main"), "the remote refused the push");
        String shown = onFx(() -> statusMessage().getText());
        assertTrue(saysCommittedButNotPushed(shown),
                "the commit button's push says the same as the shortcut's: " + shown);
        List<String> reflogAfterCommit = RepoSnapshot.of(work).headReflog();

        allowUpdatesToMainOnTheRemote();
        ButtonBase push = tab.getToolbar().toolButton("Push");
        RobotWait.untilOnFx("Push never became available", () -> !push.isDisabled());
        clickOn(push);
        RobotWait.until("the retried push never reached the remote",
                () -> committed.equals(resolve(origin, "refs/heads/main")));
        RobotWait.drainGitWork();

        assertEquals(committed, resolve(work, "refs/heads/main"), "retrying the push made no commit of its own");
        assertEquals(reflogAfterCommit, RepoSnapshot.of(work).headReflog(), "nothing moved HEAD after the commit");
        assertEquals(Map.of("refs/heads/main", committed), refs(origin),
                "the remote has the same commit hash, and nothing else");
        try (Git git = Git.open(origin.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit tip = walk.parseCommit(ObjectId.fromString(committed));
            assertEquals(oldTip, tip.getParent(0).name(),
                    "a fast-forward: the remote's old tip is the new one's parent, so nothing was rewritten");
        }
        assertEquals(committed, resolve(work, "refs/remotes/origin/main"), "origin/main followed the push");
        assertEquals(upstreamBefore, upstreamOfMain(), "the branch's upstream is untouched");
        assertEquals(NOTES_EDIT, read("notes.txt"), "the unstaged edit is still on disk");
    }

    // ------------------------------------------------------------------
    // The refusal
    // ------------------------------------------------------------------

    private Path mainLock() {
        return origin.resolve("refs").resolve("heads").resolve("main.lock");
    }

    /** Leaves a stale lock on the remote's main; see the class comment for why a lock. */
    private void refuseUpdatesToMainOnTheRemote() throws Exception {
        Files.writeString(mainLock(), resolve(origin, "refs/heads/main") + "\n", StandardCharsets.UTF_8);
    }

    private void allowUpdatesToMainOnTheRemote() throws Exception {
        Files.delete(mainLock());
    }

    // ------------------------------------------------------------------
    // The commit box and what the user is told
    // ------------------------------------------------------------------

    /** Selects the WIP row and waits for a commit button reading {@code buttonText}. */
    private void openStagingArea(String buttonText) {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("the commit box never offered \"" + buttonText + "\"", () ->
                tab.graphList().getItems().get(0).isWipNode()
                        && lookup(".composer-summary").tryQuery().isPresent()
                        && buttonText.equals(commitButton().getText()));
    }

    private TextField summary() {
        return lookup(".composer-summary").queryAs(TextField.class);
    }

    private Button commitButton() {
        return (Button) lookup(".btn-primary").queryAll().stream()
                .filter(n -> n instanceof Button b && b.getText() != null
                        && (b.getText().startsWith("Commit") || b.getText().startsWith("Nothing")))
                .findFirst().orElseThrow(() -> new AssertionError("no commit button"));
    }

    /** A key press as the summary field receives it, the caret being there after typing. */
    private void pressInSummary(KeyCode code, boolean shift, boolean ctrl) {
        interact(() -> Event.fireEvent(summary(),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, false)));
    }

    private Label statusMessage() {
        return (Label) FxHarness.lookupAll(tab.getContent(), "status-message").getFirst();
    }

    /** The text of every error notification showing. Call on the FX thread. */
    private List<String> errorToastTexts() {
        List<String> texts = new ArrayList<>();
        for (Node card : tab.toastHost().shown()) {
            if (card.getStyleClass().contains("toast-error")) {
                texts.add(((Label) FxHarness.lookupAll(card, "toast-message").getFirst()).getText());
            }
        }
        return texts;
    }

    /** Whether {@code text} says the commit was made and only the push failed. */
    private static boolean saysCommittedButNotPushed(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("committed locally") && lower.contains("push failed");
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> read) throws Exception {
        return org.testfx.util.WaitForAsyncUtils.asyncFx(read).get();
    }

    // ------------------------------------------------------------------
    // The repositories, read independently of the engine under test
    // ------------------------------------------------------------------

    /** {@code committed} is one commit, on {@code parent}, with the message and the staged tree. */
    private void assertOneCommitOnTopOf(String parent, String committed) throws Exception {
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(committed));
            assertEquals(1, commit.getParentCount(), "an ordinary commit");
            assertEquals(parent, commit.getParent(0).name(), "made on the old tip, so exactly one new commit");
            assertEquals(MESSAGE, commit.getFullMessage().strip());
            Map<String, String> tree = new TreeMap<>();
            try (TreeWalk files = new TreeWalk(git.getRepository())) {
                files.addTree(commit.getTree());
                files.setRecursive(true);
                while (files.next()) {
                    tree.put(files.getPathString(), new String(
                            git.getRepository().open(files.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
                }
            }
            assertEquals(new TreeMap<>(COMMITTED_TREE), tree, "the commit holds the index, byte for byte");
        }
    }

    private static String resolve(Path repository, String revision) {
        try (Git git = Git.open(repository.toFile())) {
            ObjectId id = git.getRepository().resolve(revision);
            return id == null ? null : id.name();
        } catch (Exception e) {
            throw new AssertionError("could not resolve " + revision + " in " + repository, e);
        }
    }

    /** Every ref under {@code refs/}; HEAD is left out, being only a name for one of them. */
    private static Map<String, String> refs(Path repository) throws Exception {
        try (Git git = Git.open(repository.toFile())) {
            Map<String, String> refs = new TreeMap<>();
            for (Ref ref : git.getRepository().getRefDatabase().getRefsByPrefix(Constants.R_REFS)) {
                refs.put(ref.getName(), ref.getObjectId() == null ? "" : ref.getObjectId().name());
            }
            return refs;
        }
    }

    private Map<String, String> upstreamOfMain() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return RepoSnapshot.upstreamOf(git.getRepository(), "main");
        }
    }

    private Status status() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return git.status().call();
        }
    }

    private String read(String file) throws Exception {
        return Files.readString(work.resolve(file), StandardCharsets.UTF_8);
    }

    private void write(String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content, StandardCharsets.UTF_8);
    }
}
