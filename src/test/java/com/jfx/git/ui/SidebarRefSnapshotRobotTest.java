package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.DelegatingGitBackend;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.graph.GraphPage;
import com.jfx.git.graph.GraphVisibility;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.StashEntry;

import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidebar's remote section and what one refresh reads to fill it.
 *
 * <p>The repository has two remotes: {@code origin}, with a branch and the symbolic
 * {@code origin/HEAD} a clone records, and {@code team/core}, whose own name has a slash.
 * The sidebar lists {@code team/core} as one folder - it used to nest a {@code core}
 * folder inside a {@code team} one - and does not list {@code origin/HEAD}, which is a
 * pointer at {@code origin/main}, not a branch.
 *
 * <p>And a refresh reads the refs once: the graph page's load reads them, and the sidebar
 * takes them from that page instead of listing branches, tags, stashes, remotes and the
 * current branch again.
 */
class SidebarRefSnapshotRobotTest extends ApplicationTest {

    @TempDir
    Path dir;

    private CountingBackend backend;
    private RepoTab tab;
    /** Whether the tab has been asked to load; a tab built on an open backend waits to be. */
    private boolean refreshed;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setString("remote", "origin", "url", dir.resolveSibling("origin.git").toString());
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.setString("remote", "team/core", "url", dir.resolveSibling("core.git").toString());
            config.setString("remote", "team/core", "fetch", "+refs/heads/*:refs/remotes/team/core/*");
            config.save();
            Files.writeString(dir.resolve("a.txt"), "a\n");
            git.add().addFilepattern("a.txt").call();
            RevCommit head = git.commit().setMessage("add a").call();
            setRef(git, "refs/remotes/origin/main", head);
            git.getRepository().updateRef("refs/remotes/origin/HEAD").link("refs/remotes/origin/main");
            setRef(git, "refs/remotes/team/core/topic", head);
        }
        // Off by default for this repository: the check reads the remote branches itself,
        // on its own schedule, and is not the sidebar's or the graph's read.
        com.jfx.git.core.RepositoryPreferences.setConflictWarning(dir.toFile(), false);

        GitService engine = new GitService();
        engine.openRepository(dir.toFile());
        backend = new CountingBackend(engine);
        tab = new RepoTab(new LyraApp(), "refs", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(() -> tab.close());
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
        com.jfx.git.core.RepositoryPreferences.setConflictWarning(dir.toFile(), null);
    }

    @Test
    void theRemoteSectionListsNoSymbolicHead() {
        TreeItem<BranchTree.Node> origin = remoteFolder("origin");

        List<String> branches = FxHarness.call(() -> origin.getChildren().stream()
                .map(item -> item.getValue().label()).toList());
        assertEquals(List.of("main"), branches, "origin/HEAD is a pointer at origin/main, not a branch");
    }

    @Test
    void aRemoteWithASlashInItsNameIsOneFolder() {
        TreeItem<BranchTree.Node> core = remoteFolder("team/core");

        List<String> branches = FxHarness.call(() -> core.getChildren().stream()
                .map(item -> item.getValue().fullName()).toList());
        assertEquals(List.of("team/core/topic"), branches);
        List<String> top = FxHarness.call(() -> remoteTree().getRoot().getChildren().stream()
                .map(item -> item.getValue().label()).toList());
        assertFalse(top.contains("team"), "not a folder team holding a folder core: " + top);
    }

    @Test
    void oneRefreshReadsTheRefsOnce() {
        remoteFolder("origin");
        RobotWait.drainGitWork();
        backend.calls.clear();

        interact(() -> tab.refresh());
        RobotWait.drainGitWork();

        Map<String, Integer> calls = new TreeMap<>();
        backend.calls.forEach((method, count) -> calls.put(method, count.get()));
        assertEquals(1, calls.getOrDefault("getCommitGraphPage", 0),
                "the page is read once: " + calls);
        calls.remove("getCommitGraphPage");
        assertEquals(Map.of(), calls, "and nothing else lists the refs or reads the current branch");
    }

    /**
     * A remote added since the last refresh changes no ref the graph draws, so the page
     * that comes back draws the same and is left on screen - and the sidebar still lists
     * the new remote, from that page's snapshot.
     */
    @Test
    void aNewRemoteIsListedThoughThePageDrawsTheSame() throws Exception {
        remoteFolder("origin");
        RobotWait.drainGitWork();
        try (Git git = Git.open(dir.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "upstream", "url", dir.resolveSibling("upstream.git").toString());
            config.save();
        }

        interact(() -> tab.refresh());

        RobotWait.untilOnFx("the new remote was never listed", () -> {
            TreeView<BranchTree.Node> tree = remoteTree();
            return tree.getRoot() != null && find(tree.getRoot(), "upstream") != null;
        });
    }

    // ------------------------------------------------------------------
    // A page that cannot be read
    // ------------------------------------------------------------------

    /**
     * A page that cannot be read - a branch at an object that is not there, a corrupt
     * pack - brings no refs, and the sidebar reads them itself rather than drawing from
     * none: the branches are there to check out or delete, origin is listed rather than
     * "Add a remote…", and the toolbar's switcher and the palette have them too.
     */
    @Test
    void aPageThatCannotBeReadLeavesTheSidebarAndToolbarTheirRefs() {
        backend.pageFails = true;

        TreeItem<BranchTree.Node> origin = remoteFolder("origin");

        assertEquals(List.of("main"), FxHarness.call(() -> origin.getChildren().stream()
                .map(item -> item.getValue().label()).toList()));
        RobotWait.untilOnFx("LOCAL never listed main", () -> branchRow(localTree().getRoot(), "main") != null);
        interact(() -> {
            assertFalse(offersAddRemote(), "origin is there: no \"Add a remote…\"");
            javafx.scene.control.ComboBox<?> switcher =
                    (javafx.scene.control.ComboBox<?>) tab.getToolbar().lookup(".combo-box");
            assertEquals("main", switcher.getValue(), "the switcher names the current branch");
            assertEquals(List.of("main"), List.copyOf(switcher.getItems()));
            assertEquals(List.of("main"), tab.getSidebar().cachedLocalBranches(), "what the palette checks out");
        });
        assertTrue(backend.calls.containsKey("getRefSnapshot"), "read directly, the page having brought none");
    }

    /**
     * A page that fails on a later refresh leaves the refs as they are now, not as the last
     * page that loaded had them: a branch made since is listed, in the sidebar and the
     * toolbar.
     */
    @Test
    void aPageThatFailsLaterLeavesTheRefsAsTheyAreNow() throws Exception {
        remoteFolder("origin");
        RobotWait.drainGitWork();
        try (Git git = Git.open(dir.toFile())) {
            setRef(git, "refs/heads/fresh", git.getRepository().parseCommit(git.getRepository().resolve("HEAD")));
        }
        backend.pageFails = true;

        interact(() -> tab.refresh());

        RobotWait.untilOnFx("the branch made since the last page was never listed",
                () -> branchRow(localTree().getRoot(), "fresh") != null);
        RobotWait.untilOnFx("the toolbar's switcher never had it", () -> ((javafx.scene.control.ComboBox<?>)
                tab.getToolbar().lookup(".combo-box")).getItems().contains("fresh"));
    }

    /**
     * The sidebar's own read, dropped before it started - cancelled from the status bar -
     * is over all the same: the refs a graph-only refresh hands on afterwards are drawn.
     * It never answers, and the sidebar used to wait for it for ever, drawing again only
     * when a refresh of the whole tab read for it once more.
     */
    @Test
    void aSidebarReadDroppedBeforeItStartedHoldsNoLaterRefsBack() throws Exception {
        remoteFolder("origin");
        RobotWait.drainGitWork();
        com.jfx.git.model.RefSnapshot page = pageWithFresh();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        com.jfx.git.core.GitTaskRunner.Task[] read = new com.jfx.git.core.GitTaskRunner.Task[1];
        try {
            interact(() -> {
                // The lane held, so the read waits behind it.
                holdTheLane(release);
                com.jfx.git.core.GitTaskRunner.claimNextTask(task -> read[0] = task);
                tab.getSidebar().refresh();
            });
            assertTrue(read[0] != null, "the sidebar's read was queued");
            interact(() -> read[0].cancel());
        } finally {
            release.countDown();
        }
        RobotWait.drainGitWork();

        interact(() -> tab.getSidebar().showRefs(page));

        RobotWait.untilOnFx("the refs handed on after the dropped read were never drawn",
                () -> branchRow(localTree().getRoot(), "fresh") != null);
    }

    /**
     * Refs that came while the sidebar's own read was out are drawn when that read fails,
     * beside the last one that worked, rather than dropped with it.
     */
    @Test
    void refsThatCameDuringAnOwnReadThatFailedAreStillDrawn() throws Exception {
        remoteFolder("origin");
        RobotWait.drainGitWork();
        com.jfx.git.model.RefSnapshot page = pageWithFresh();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            interact(() -> {
                holdTheLane(release);
                tab.getSidebar().refresh();
                // The page's refs, landing while the sidebar's own read waits.
                tab.getSidebar().showRefs(page);
            });
            backend.ownReadFails = true;
        } finally {
            release.countDown();
        }
        try {
            RobotWait.untilOnFx("the refs that came during the failed read were never drawn",
                    () -> branchRow(localTree().getRoot(), "fresh") != null);
        } finally {
            RobotWait.drainGitWork();
            backend.ownReadFails = false;
        }
    }

    // ------------------------------------------------------------------

    /** Main, origin's and team/core's branches, as the fixture has them, and a branch {@code fresh} besides. */
    private com.jfx.git.model.RefSnapshot pageWithFresh() throws Exception {
        String head;
        try (Git git = Git.open(dir.toFile())) {
            head = git.getRepository().resolve("HEAD").name();
        }
        return new com.jfx.git.model.RefSnapshot("main", head,
                List.of(new RefInfo("refs/heads/main", "main", head), new RefInfo("refs/heads/fresh", "fresh", head)),
                List.of(new RefInfo("refs/remotes/origin/main", "origin/main", head),
                        new RefInfo("refs/remotes/team/core/topic", "team/core/topic", head)),
                List.of(), Map.of(), List.of(), List.of("origin", "team/core"));
    }

    /** Holds the tab's lane until {@code release}: the tab's own backend, which wraps this test's. */
    private void holdTheLane(java.util.concurrent.CountDownLatch release) {
        com.jfx.git.core.GitTaskRunner.run(tab.getGitService(), () -> {
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return com.jfx.git.core.GitResult.ok("held");
        }, result -> { });
    }

    /** The branch row for {@code name} under {@code parent}, at any depth; null when there is none. */
    private static TreeItem<BranchTree.Node> branchRow(TreeItem<BranchTree.Node> parent, String name) {
        if (parent == null) return null;
        for (TreeItem<BranchTree.Node> child : parent.getChildren()) {
            if (name.equals(child.getValue().fullName())) return child;
            TreeItem<BranchTree.Node> deeper = branchRow(child, name);
            if (deeper != null) return deeper;
        }
        return null;
    }

    /** Whether the remote section offers "Add a remote…", as it does only with no remote at all. */
    private boolean offersAddRemote() {
        return FxHarness.lookupAll(tab.getContent(), "button").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Button b
                        && "Add a remote…".equals(b.getText()) && b.isVisible());
    }

    @SuppressWarnings("unchecked")
    private TreeView<BranchTree.Node> localTree() {
        return (TreeView<BranchTree.Node>) FxHarness.lookupAll(tab.getContent(), "branch-tree").get(0);
    }

    /** The remote folder whose path is {@code path}, once the sidebar has drawn it. */
    private TreeItem<BranchTree.Node> remoteFolder(String path) {
        if (!refreshed) {
            refreshed = true;
            interact(() -> tab.refresh());
        }
        @SuppressWarnings("unchecked")
        TreeItem<BranchTree.Node>[] found = new TreeItem[1];
        RobotWait.untilOnFx("the remote section never showed " + path, () -> {
            TreeView<BranchTree.Node> tree = remoteTree();
            if (tree.getRoot() == null) return false;
            found[0] = find(tree.getRoot(), path);
            return found[0] != null && !found[0].getChildren().isEmpty();
        });
        return found[0];
    }

    private static TreeItem<BranchTree.Node> find(TreeItem<BranchTree.Node> parent, String path) {
        for (TreeItem<BranchTree.Node> child : parent.getChildren()) {
            if (child.getValue().fullName() == null && path.equals(BranchTree.pathOf(child))) return child;
            TreeItem<BranchTree.Node> deeper = find(child, path);
            if (deeper != null) return deeper;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private TreeView<BranchTree.Node> remoteTree() {
        return (TreeView<BranchTree.Node>) FxHarness.lookupAll(tab.getContent(), "branch-tree").get(1);
    }

    private static void setRef(Git git, String name, RevCommit commit) throws Exception {
        RefUpdate update = git.getRepository().updateRef(name);
        update.setNewObjectId(commit);
        update.setForceUpdate(true);
        update.update();
    }

    /**
     * The JGit engine, counting the calls that list refs or read the current branch, and
     * the graph page loads - the reads a ref snapshot replaces.
     */
    private static final class CountingBackend extends DelegatingGitBackend {
        private final GitBackend engine;
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        /** Whether the graph page cannot be read, as for a branch at an object that is not there. */
        volatile boolean pageFails;
        /** Whether the sidebar's own read fails, at its first question: the tracking counts. */
        volatile boolean ownReadFails;

        CountingBackend(GitBackend engine) {
            this.engine = engine;
        }

        private void count(String method) {
            calls.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
            if (pageFails && method.equals("getCommitGraphPage")) {
                throw new IllegalStateException("Missing unknown 0123456789abcdef0123456789abcdef01234567");
            }
        }

        @Override protected GitBackend reader() { return engine; }
        @Override protected GitBackend writer() { return engine; }

        @Override public boolean openRepository(java.io.File directory) throws com.jfx.git.core.GitOperationException {
            return engine.openRepository(directory);
        }
        @Override public boolean isOpen() { return engine.isOpen(); }
        @Override public void close() { engine.close(); }
        @Override public java.io.File getWorkTree() { return engine.getWorkTree(); }
        @Override public java.io.File getDirectory() { return engine.getDirectory(); }

        @Override public List<RefInfo> getLocalBranches() { count("getLocalBranches"); return super.getLocalBranches(); }
        @Override public List<RefInfo> getRemoteBranches() { count("getRemoteBranches"); return super.getRemoteBranches(); }
        @Override public List<RefInfo> getTags() { count("getTags"); return super.getTags(); }
        @Override public String getCurrentBranch() { count("getCurrentBranch"); return super.getCurrentBranch(); }
        @Override public List<StashEntry> getStashEntries() { count("getStashEntries"); return super.getStashEntries(); }
        @Override public List<String> getRemoteNames() { count("getRemoteNames"); return super.getRemoteNames(); }
        @Override public Map<String, String> getUpstreams() { count("getUpstreams"); return super.getUpstreams(); }
        @Override public int[] getTrackingStatus() {
            if (ownReadFails) throw new IllegalStateException("could not read the tracking counts");
            return super.getTrackingStatus();
        }
        @Override public com.jfx.git.model.RefSnapshot getRefSnapshot() {
            count("getRefSnapshot");
            return super.getRefSnapshot();
        }

        @Override public GraphPage getCommitGraphPage(int limit, GraphOrder order) {
            count("getCommitGraphPage");
            return super.getCommitGraphPage(limit, order);
        }
        @Override public GraphPage getCommitGraphPage(int limit, GraphOrder order, GraphVisibility visibility) {
            count("getCommitGraphPage");
            return super.getCommitGraphPage(limit, order, visibility);
        }
        @Override public GraphPage getCommitGraphPageVisible(int limit) {
            count("getCommitGraphPage");
            return super.getCommitGraphPageVisible(limit);
        }
    }
}
