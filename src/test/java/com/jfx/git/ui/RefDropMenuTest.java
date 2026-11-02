package com.jfx.git.ui;

import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.ui.RefDropMenu.Ref;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What dropping one branch on another offers, and what each offer does to a real
 * repository with a real bare remote.
 *
 * <p>The menu is built and its items fired directly. The drag that leads to it is the
 * window system's; which menu a pair of branches gets, and what its items do, is the
 * part that is this application's and that a sidebar row and a graph chip now share.
 */
class RefDropMenuTest {

    @TempDir
    Path root;

    private Path workTree;
    private Path originDir;
    private GitService service;
    private StatusBar statusBar;
    private RefDropMenu drops;

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @BeforeEach
    void setUp() throws Exception {
        workTree = Files.createDirectory(root.resolve("work"));
        originDir = root.resolve("origin.git");
        try (Git git = Git.init().setDirectory(workTree.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        Git.init().setDirectory(originDir.toFile()).setBare(true).setInitialBranch("main").call().close();

        service = new GitService();
        service.openRepository(workTree.toFile());
        commit("a.txt", "a", "add a");
        assertTrue(service.addRemote("origin", originDir.toString()).isOk());
        assertTrue(new RepositoryCommands(service).push("origin", false, false, true).isOk());

        // A branch with one commit of its own, never pushed; HEAD back on main.
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("f.txt", "f", "add f");
        assertTrue(service.checkoutBranch("main").isOk());

        statusBar = FxHarness.call(StatusBar::new);
        drops = new RefDropMenu(OperationRunner.of(service, statusBar, () -> { }));
    }

    @AfterEach
    void tearDown() {
        RobotWait.drainGitWork();
        if (service != null) {
            service.close();
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(workTree.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private List<String> labels(Ref source, Ref target, String current) {
        return FxHarness.call(() -> drops.build(source, target, current).getItems().stream()
                .map(MenuItem::getText).toList());
    }

    private void fire(Ref source, Ref target, String current, String label) {
        FxHarness.run(() -> {
            ContextMenu menu = drops.build(source, target, current);
            menu.getItems().stream().filter(i -> label.equals(i.getText())).findFirst()
                    .orElseThrow(() -> new AssertionError("no \"" + label + "\" among "
                            + menu.getItems().stream().map(MenuItem::getText).toList()))
                    .fire();
        });
    }

    private boolean originHas(String ref) {
        try (Git bare = Git.open(originDir.toFile())) {
            return bare.getRepository().getRefDatabase().exactRef(ref) != null;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------
    // What is on the dragboard
    // ------------------------------------------------------------------

    @Test
    void aRefSurvivesTheDragboardWithItsKind() {
        assertEquals(Ref.local("feature/x"), Ref.decode(Ref.local("feature/x").encode()));
        assertEquals(Ref.remote("origin/feature/x"), Ref.decode(Ref.remote("origin/feature/x").encode()));
        assertEquals(Ref.local("odd:name"), Ref.decode(Ref.local("odd:name").encode()),
                "a colon in a branch name is not the separator");
    }

    /** Text dragged in from an editor is not a branch, however much it looks like one. */
    @Test
    void textThatIsNotOursIsNotABranch() {
        assertNull(Ref.decode("main"));
        assertNull(Ref.decode(null));
        assertNull(Ref.decode("lyra-ref:"));
        assertNull(Ref.decode("lyra-ref:LOCAL:"));
        assertNull(Ref.decode("lyra-ref:SIDEWAYS:main"));
    }

    // ------------------------------------------------------------------
    // Which menu a pair gets
    // ------------------------------------------------------------------

    @Test
    void aLocalBranchOnALocalBranchOffersMergeAndRebase() {
        assertEquals(List.of("Merge feature into main", "Merge feature into main with options…",
                        "Rebase feature onto main"),
                labels(Ref.local("feature"), Ref.local("main"), "main"));
    }

    @Test
    void aRemoteBranchOnALocalBranchOffersMergeAndTrackingButNoRebase() {
        assertEquals(List.of("Merge origin/main into feature", "Merge origin/main into feature with options…",
                        "Set origin/main as upstream of feature"),
                labels(Ref.remote("origin/main"), Ref.local("feature"), "main"),
                "a remote branch is not this clone's to rebase");
    }

    @Test
    void aLocalBranchOnARemoteBranchOffersPushAndTracking() {
        assertEquals(List.of("Push main to origin", "Set origin/main as upstream of main"),
                labels(Ref.local("main"), Ref.remote("origin/main"), "main"));
        assertEquals(List.of("Checkout feature and push it to origin", "Set origin/main as upstream of feature"),
                labels(Ref.local("feature"), Ref.remote("origin/main"), "main"),
                "push sends the branch that is checked out, so the item says when it has to switch first");
    }

    @Test
    void pairsThatMeanNothingOfferNothing() {
        assertEquals(List.of(), labels(Ref.local("main"), Ref.local("main"), "main"), "a branch on itself");
        assertEquals(List.of(), labels(Ref.remote("origin/main"), Ref.remote("origin/other"), "main"),
                "neither end is this clone's to change");
        assertFalse(RefDropMenu.accepts(null, Ref.local("main")));
        assertFalse(RefDropMenu.accepts(Ref.local("main"), null));
        assertTrue(RefDropMenu.accepts(Ref.local("feature"), Ref.local("main")));
    }

    // ------------------------------------------------------------------
    // What the items do
    // ------------------------------------------------------------------

    @Test
    void mergingChecksOutTheTargetAndMergesTheSourceIntoIt() {
        assertTrue(service.createBranch("other", null, true).isOk());
        assertEquals("other", service.getCurrentBranch());

        fire(Ref.local("feature"), Ref.local("main"), "other", "Merge feature into main");
        RobotWait.until("feature was never merged into main",
                () -> Files.exists(workTree.resolve("f.txt")) && "main".equals(service.getCurrentBranch()));
    }

    @Test
    void pushingABranchThatIsNotCheckedOutSwitchesToItAndPublishesIt() {
        assertFalse(originHas("refs/heads/feature"), "sanity: feature has never been pushed");

        fire(Ref.local("feature"), Ref.remote("origin/main"), "main", "Checkout feature and push it to origin");
        RobotWait.until("feature never reached origin", () -> originHas("refs/heads/feature"));
        RobotWait.drainGitWork();

        assertEquals("feature", service.getCurrentBranch(), "as the item said it would");
        assertEquals("origin/feature", service.getUpstream("feature"),
                "a branch's first push records where it went");
    }

    @Test
    void droppingARemoteBranchOnALocalOneCanMakeItTheUpstream() {
        assertNull(service.getUpstream("feature"));

        fire(Ref.remote("origin/main"), Ref.local("feature"), "main", "Set origin/main as upstream of feature");
        RobotWait.until("feature never tracked origin/main",
                () -> "origin/main".equals(service.getUpstream("feature")));
        assertEquals("main", service.getCurrentBranch(), "setting an upstream checks nothing out");
        assertNotNull(service.getUpstream("main"));
    }
}
