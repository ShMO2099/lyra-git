package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;

import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
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
 * Push and Pull, against a real remote rather than only their no-remote failure path.
 *
 * <h2>Why a bare repository rather than another stub</h2>
 * {@code RepoToolbarRobotTest} proves Fetch reaches {@link GitService} using the
 * deterministic failure of a repository with no remote configured. That is enough to
 * prove wiring, but it cannot tell three pull modes apart, or tell a real push from one
 * that silently failed the same way every time — the three variants under Pull's arrow,
 * and Push's plain/tags/force-with-lease, differ only in what they do to a real remote.
 * A local bare repository gives each test something to check afterwards: a commit that
 * did or did not arrive, a tag that exists, a rejection that really happened.
 *
 * <h2>Reaching the split-button menus under Monocle Headless</h2>
 * A native right-click does not become a context-menu request under Monocle Headless —
 * {@code MonocleCapabilitiesTest} pins that for the graph's column menu, and the same
 * platform limitation applies to opening a {@link SplitMenuButton}'s arrow by a robot
 * click on it. The menu is opened programmatically with {@code show()} instead; every
 * click on an entry inside it afterwards is a real robot click, which is the half that
 * matters and the half Monocle can do.
 */
class RepoToolbarRemoteRobotTest extends ApplicationTest {

    // NEVER, not the default, on both: localDir is opened by every test's own push/pull
    // *and* by the throwaway "another clone" each pull test creates, and remoteDir by
    // all of those plus every push - so both are contested paths in the class. JGit's
    // Windows behaviour makes that a real problem - see tidyUp() - and asking JUnit to
    // strictly delete either turns a benign OS-level race that has nothing to do with
    // correctness into a failed test. The directories left behind are a handful of
    // kilobytes of throwaway git objects; the OS temp folder is not somewhere this
    // project promises to keep clean between runs.
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path localDir;
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path remoteDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();

        try (Git git = Git.init().setDirectory(localDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }

        service = new GitService();
        service.openRepository(localDir.toFile());
        assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // A push or pull returns from its click long before it finishes; let it finish
        // before the backend is closed under it.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
        } else if (service != null) {
            service.close();
        }
        // See RepoToolbarRobotTest: a dialog or Alert a failed assertion leaves open
        // outlives its test and can be what a later test's lookup finds.
        interact(() -> new java.util.ArrayList<>(javafx.stage.Window.getWindows())
                .forEach(javafx.stage.Window::hide));

        // JGit keeps every Repository it opens - including the bare remote, opened
        // internally by the local-transport push/clone in pushFromAnotherClone() and
        // never handed back to this class to close - in a process-wide
        // RepositoryCache, memory-mapping each pack file for reuse. Closing the Git
        // wrapper that opened one does not evict it from the cache. On Windows a
        // memory-mapped file cannot be deleted while mapped, so @TempDir's cleanup of
        // remoteDir or a clone directory failed with "process cannot access the file"
        // even though every assertion above it had already passed.
        //
        // Clearing the cache closes the Repository objects, but on Windows close()
        // alone does not guarantee the OS actually releases a memory-mapped pack file -
        // JGit unmaps it via a Cleaner tied to the MappedByteBuffer's own garbage
        // collection, not to the close() call. System.gc() is what makes that run
        // promptly instead of whenever the JVM next feels like it; without it this was
        // still flaky, closing two directories out of three rather than three of three.
        org.eclipse.jgit.lib.RepositoryCache.clear();
        System.gc();
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void commitLocally(String name, String content) throws Exception {
        Files.writeString(localDir.resolve(name), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(name).isOk());
        assertTrue(service.commit("add " + name, false).isOk());
        refreshToolbar();
    }

    /**
     * Refreshes the toolbar and lets its queries finish: they run on the git lane, and the
     * tests go on to call the service directly from this thread, which must not interleave
     * with them.
     */
    private void refreshToolbar() {
        interact(() -> toolbar().refresh());
        RobotWait.drainGitWork();
    }

    /** Pushes the current commit and fetches, so {@code origin/main} tracks it locally. */
    private void establishUpstream() {
        assertTrue(new RepositoryCommands(service).push("origin", false, false, true).isOk());
        assertTrue(service.fetchAll().isOk());
    }

    /**
     * Clones the bare remote into {@code cloneDir} and commits there, moving the remote
     * ahead of anything the local repository under test knows about — the shape of "a
     * teammate pushed while you were away".
     */
    private void pushFromAnotherClone(Path cloneDir, String name, String content) throws Exception {
        try (Git other = Git.cloneRepository()
                .setURI(remoteDir.toUri().toString())
                .setDirectory(cloneDir.toFile())
                .call()) {
            var config = other.getRepository().getConfig();
            config.setString("user", null, "name", "Other User");
            config.setString("user", null, "email", "other@example.com");
            config.save();
            Files.writeString(cloneDir.resolve(name), content, StandardCharsets.UTF_8);
            other.add().addFilepattern(name).call();
            other.commit().setMessage("other: add " + name).call();
            other.push().call();
        }
    }

    private RevCommit remoteTip() throws Exception {
        try (Git remote = Git.open(remoteDir.toFile())) {
            Ref head = remote.getRepository().exactRef("refs/heads/master");
            if (head == null) {
                head = remote.getRepository().exactRef("refs/heads/main");
            }
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(remote.getRepository())) {
                return walk.parseCommit(head.getObjectId());
            }
        }
    }

    private ObjectId localHead() throws Exception {
        try (Git git = Git.open(localDir.toFile())) {
            return git.getRepository().resolve("HEAD");
        }
    }

    /** Whether the remote's branch is at {@code expected}; a remote mid-push reads as "not yet". */
    private boolean remoteIsAt(ObjectId expected) {
        try {
            return expected.equals(remoteTip().getId());
        } catch (Exception midPush) {
            return false;
        }
    }

    /** Whether the remote has {@code refName}; a remote mid-push reads as "not yet". */
    private boolean remoteHasRef(String refName) {
        try (Git remote = Git.open(remoteDir.toFile())) {
            return remote.getRepository().exactRef(refName) != null;
        } catch (Exception midPush) {
            return false;
        }
    }

    /** Whether the local remote-tracking ref has caught up with the remote's branch. */
    private boolean trackingMatchesRemote() {
        try (Git git = Git.open(localDir.toFile())) {
            Ref tracking = git.getRepository().exactRef("refs/remotes/origin/master") != null
                    ? git.getRepository().exactRef("refs/remotes/origin/master")
                    : git.getRepository().exactRef("refs/remotes/origin/main");
            return tracking != null && remoteTip().getId().equals(tracking.getObjectId());
        } catch (Exception midFetch) {
            return false;
        }
    }

    private RepoToolbar toolbar() {
        return tab.getToolbar();
    }

    private ButtonBase button(String label) {
        return toolbar().toolButton(label);
    }

    /** Clicks a toolbar button. The work it starts is still running when this returns. */
    private void click(String label) {
        clickOn(button(label));
    }

    /**
     * Opens a split button's menu programmatically and clicks the named entry. The work
     * it starts is still running when this returns.
     */
    private void chooseFromMenu(String buttonLabel, String itemLabel) {
        SplitMenuButton split = (SplitMenuButton) button(buttonLabel);
        // The toolbar disables itself while an earlier action runs, and show() does
        // nothing on a disabled button.
        RobotWait.untilOnFx("the " + buttonLabel + " button never became enabled",
                () -> !split.isDisabled());
        interact(split::show);
        settle();
        clickOn(itemLabel);
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

    // ------------------------------------------------------------------
    // Push
    // ------------------------------------------------------------------

    @Test
    void thePushButtonPushesARealCommit() throws Exception {
        commitLocally("a.txt", "a");

        click("Push");
        ObjectId head = localHead();
        RobotWait.until("the push never reached the remote", () -> remoteIsAt(head));

        assertEquals(localHead(), remoteTip().getId(),
                "the button press should have moved the remote to the local commit");
    }

    @Test
    void pushWithTagsSendsATagToTheRemote() throws Exception {
        commitLocally("a.txt", "a");
        establishUpstream();
        assertTrue(service.createTag("v1", "release", "HEAD").isOk());

        chooseFromMenu("Push", "Push with tags");
        RobotWait.until("the tag never reached the remote", () -> remoteHasRef("refs/tags/v1"));

        try (Git remote = Git.open(remoteDir.toFile())) {
            assertNotNull(remote.getRepository().exactRef("refs/tags/v1"),
                    "the tag should have reached the remote, not only the commit");
        }
    }

    /**
     * Force-with-lease succeeding: the local diverges from what was pushed, but the
     * remote itself has not moved since — the lease matches, so the force is honoured.
     */
    @Test
    void forcePushWithLeaseOverwritesWhenTheLeaseStillMatches() throws Exception {
        commitLocally("a.txt", "a");
        establishUpstream();

        // Rewrite history locally without touching the remote: amend the same commit
        // into something else, so a plain push is a rejected non-fast-forward.
        Files.writeString(localDir.resolve("a.txt"), "a-rewritten", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("rewritten", true).isOk());
        refreshToolbar();

        ObjectId beforeForce = remoteTip().getId();
        click("Push");
        // Nothing to wait for: the remote is supposed to stay where it is. Let the push
        // finish, so "unchanged" means it was refused rather than not yet attempted.
        RobotWait.drainGitWork();
        assertEquals(beforeForce, remoteTip().getId(),
                "a plain push must not have force-updated a diverged remote");

        chooseFromMenu("Push", "Force push (with lease)");
        // The confirmation Alert is a second top-level window, opened once the lease has
        // been read off the FX thread; TestFX's default button activation (Enter) confirms
        // it the same way clicking OK would.
        RobotWait.untilDialogWithButton("OK");
        push(javafx.scene.input.KeyCode.ENTER);
        ObjectId head = localHead();
        RobotWait.until("the force push never reached the remote", () -> remoteIsAt(head));

        assertEquals(localHead(), remoteTip().getId(),
                "force-with-lease should have overwritten the remote once confirmed");
    }

    /**
     * Force-with-lease refusing: the remote moved to a commit this repository never
     * fetched, so the lease this push carries is stale. This is the property the whole
     * feature exists for — it must not clobber someone else's work just because the
     * local branch also diverged.
     */
    @Test
    void forcePushWithLeaseRefusesWhenTheRemoteMovedUnexpectedly(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path otherClone)
            throws Exception {
        commitLocally("a.txt", "a");
        establishUpstream();

        pushFromAnotherClone(otherClone, "b.txt", "from someone else");
        RevCommit remoteAfterOthers = remoteTip();

        Files.writeString(localDir.resolve("a.txt"), "a-rewritten-too", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("rewritten locally", true).isOk());
        refreshToolbar();

        chooseFromMenu("Push", "Force push (with lease)");
        RobotWait.untilDialogWithButton("OK");
        push(javafx.scene.input.KeyCode.ENTER);
        // The remote must stay put, so wait for the push to finish rather than for a change.
        RobotWait.drainGitWork();

        assertEquals(remoteAfterOthers.getId(), remoteTip().getId(),
                "a stale lease must refuse rather than overwrite work this repository "
                        + "never even fetched");
    }

    // ------------------------------------------------------------------
    // Pull
    // ------------------------------------------------------------------

    @Test
    void thePullButtonBringsDownARealCommit(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path otherClone) throws Exception {
        commitLocally("a.txt", "a");
        establishUpstream();
        pushFromAnotherClone(otherClone, "b.txt", "from origin");

        click("Pull");
        RobotWait.until("the pull never brought b.txt down",
                () -> Files.exists(localDir.resolve("b.txt")));

        assertTrue(Files.exists(localDir.resolve("b.txt")),
                "pulling should have brought the other commit's file down");
    }

    @Test
    void pullFastForwardOnlySucceedsWhenTheLocalHasNotDiverged(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path otherClone)
            throws Exception {
        commitLocally("a.txt", "a");
        establishUpstream();
        pushFromAnotherClone(otherClone, "b.txt", "from origin");

        chooseFromMenu("Pull", "Pull (fast-forward only)");
        RobotWait.until("the fast-forward never brought b.txt down",
                () -> Files.exists(localDir.resolve("b.txt")));

        assertTrue(Files.exists(localDir.resolve("b.txt")),
                "a clean fast-forward should be allowed through, not just the plain Pull");
    }

    /**
     * The property that distinguishes this mode from the plain Pull button: it must
     * refuse rather than silently merge once both sides have moved.
     */
    @Test
    void pullFastForwardOnlyRefusesWhenBothSidesHaveDiverged(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path otherClone)
            throws Exception {
        commitLocally("a.txt", "a");
        establishUpstream();
        pushFromAnotherClone(otherClone, "b.txt", "from origin");
        commitLocally("c.txt", "local divergence");

        chooseFromMenu("Pull", "Pull (fast-forward only)");
        // A refusal leaves nothing to wait for; wait for the pull itself to finish instead.
        RobotWait.drainGitWork();

        assertFalse(Files.exists(localDir.resolve("b.txt")),
                "a non-fast-forward pull must be refused by this mode, not merged anyway");
    }

    @Test
    void pullRebaseReplaysLocalWorkOnTopRatherThanMerging(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path otherClone)
            throws Exception {
        commitLocally("a.txt", "a");
        establishUpstream();
        pushFromAnotherClone(otherClone, "b.txt", "from origin");
        commitLocally("c.txt", "local work");

        chooseFromMenu("Pull", "Pull (rebase)");
        // Both at once: a rebase checks out the upstream first, which removes c.txt until
        // the local commit is replayed on top.
        RobotWait.until("the rebase never finished", () -> Files.exists(localDir.resolve("b.txt"))
                && Files.exists(localDir.resolve("c.txt")));

        assertTrue(Files.exists(localDir.resolve("b.txt")), "the remote's commit should be present");
        assertTrue(Files.exists(localDir.resolve("c.txt")), "and the local commit preserved");

        try (Git git = Git.open(localDir.toFile())) {
            for (RevCommit c : git.log().call()) {
                assertTrue(c.getParentCount() <= 1,
                        "a rebase replays commits one after another; a merge commit here "
                                + "means Pull (rebase) actually merged");
            }
        }
    }

    @Test
    void fetchAllFromThePullMenuUpdatesRemoteTrackingWithoutTouchingTheLocalBranch(
            @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path otherClone) throws Exception {
        commitLocally("a.txt", "a");
        establishUpstream();
        pushFromAnotherClone(otherClone, "b.txt", "from origin");

        chooseFromMenu("Pull", "Fetch all");
        RobotWait.until("the fetch never moved the remote-tracking ref", this::trackingMatchesRemote);

        assertFalse(Files.exists(localDir.resolve("b.txt")),
                "fetch alone must not touch the working tree");
        try (Git git = Git.open(localDir.toFile())) {
            var tracking = git.getRepository().exactRef("refs/remotes/origin/master") != null
                    ? git.getRepository().exactRef("refs/remotes/origin/master")
                    : git.getRepository().exactRef("refs/remotes/origin/main");
            assertNotNull(tracking, "fetch should have created or moved a remote-tracking ref");
            assertEquals(remoteTip().getId(), tracking.getObjectId(),
                    "and it should point at what the remote actually has now");
        }
    }

    // ------------------------------------------------------------------
    // Push to…
    // ------------------------------------------------------------------

    private boolean forkIsAt(Path forkDir, ObjectId expected) {
        try (Git fork = Git.open(forkDir.toFile())) {
            for (Ref ref : fork.getRepository().getRefDatabase().getRefsByPrefix("refs/heads/")) {
                if (expected.equals(ref.getObjectId())) return true;
            }
            return false;
        } catch (Exception e) {
            throw new AssertionError("could not read " + forkDir, e);
        }
    }

    private void pressInDialog(javafx.scene.control.DialogPane pane, String button) {
        interact(() -> {
            var type = pane.getButtonTypes().stream().filter(bt -> button.equals(bt.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("no " + button + " button"));
            ((javafx.scene.control.Button) pane.lookupButton(type)).fire();
        });
    }

    /**
     * Push goes where the branch is configured to go. "Push to…" is for the other remote:
     * the branch lands there, and what it tracks is left alone unless asked.
     */
    @Test
    void pushToSendsTheBranchToTheChosenRemoteAndKeepsWhatItTracks(
            @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path forkDir) throws Exception {
        Git.init().setDirectory(forkDir.toFile()).setBare(true).call().close();
        commitLocally("a.txt", "a");
        establishUpstream();
        assertTrue(service.addRemote("fork", forkDir.toString()).isOk());
        commitLocally("b.txt", "b");
        String branch = service.getCurrentBranch();
        ObjectId head = localHead();
        ObjectId originBefore = remoteTip().getId();

        chooseFromMenu("Push", "Push to…");
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("Push");
        interact(() -> {
            @SuppressWarnings("unchecked")
            javafx.scene.control.ChoiceBox<String> remote = (javafx.scene.control.ChoiceBox<String>)
                    dialog.lookup("#" + com.jfx.git.ui.dialogs.PushToDialog.REMOTE_ID);
            assertEquals("fork", remote.getValue(),
                    "the remote the branch does not already track is the one this is for");
            javafx.scene.control.CheckBox track = (javafx.scene.control.CheckBox)
                    dialog.lookup("#" + com.jfx.git.ui.dialogs.PushToDialog.TRACK_ID);
            assertFalse(track.isSelected(), "a branch that tracks origin keeps tracking it by default");
            assertFalse(track.isDisabled());
            assertTrue(dialog.getButtonTypes().stream()
                            .map(type -> ((javafx.scene.control.Button) dialog.lookupButton(type)).getText())
                            .anyMatch("Push to fork"::equals),
                    "the button names the remote the push goes to");
        });
        pressInDialog(dialog, "Push");

        RobotWait.until("the branch never reached the fork", () -> forkIsAt(forkDir, head));
        RobotWait.drainGitWork();
        assertEquals(originBefore, remoteTip().getId(), "origin was not the remote chosen");
        assertEquals("origin/" + branch, service.getUpstream(branch),
                "pushing a copy to a fork must not re-point the upstream");
    }

    @Test
    void pushToCanMoveTheUpstreamToTheChosenRemote(
            @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path forkDir) throws Exception {
        Git.init().setDirectory(forkDir.toFile()).setBare(true).call().close();
        commitLocally("a.txt", "a");
        establishUpstream();
        assertTrue(service.addRemote("fork", forkDir.toString()).isOk());
        String branch = service.getCurrentBranch();
        ObjectId head = localHead();

        chooseFromMenu("Push", "Push to…");
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("Push");
        interact(() -> ((javafx.scene.control.CheckBox) dialog.lookup(
                "#" + com.jfx.git.ui.dialogs.PushToDialog.TRACK_ID)).setSelected(true));
        pressInDialog(dialog, "Push");

        RobotWait.until("the branch never reached the fork", () -> forkIsAt(forkDir, head));
        RobotWait.until("the upstream never moved to the fork",
                () -> ("fork/" + branch).equals(service.getUpstream(branch)));
    }

    /** With nowhere to push there is nothing to ask; the status bar says what is missing. */
    @Test
    void pushToWithNoRemotesSaysSoInsteadOfOpeningAnEmptyDialog() throws Exception {
        commitLocally("a.txt", "a");
        assertTrue(service.removeRemote("origin").isOk());

        chooseFromMenu("Push", "Push to…");
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> assertTrue(javafx.stage.Window.getWindows().stream()
                        .noneMatch(w -> w instanceof Stage s && s.isShowing() && s != tab.getTabPane().getScene().getWindow()
                                && s.getScene() != null
                                && s.getScene().getRoot() instanceof javafx.scene.control.DialogPane),
                "no dialog should open when there is no remote to choose"));
    }
}
