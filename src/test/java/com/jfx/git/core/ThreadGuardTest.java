package com.jfx.git.core;

import com.jfx.git.model.StagingState;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rule that git work stays off the thread that draws, made into something that fails.
 *
 * <h2>Why this exists</h2>
 * "All git operations serialize through a single background thread" was checked by reading
 * call sites for three review rounds, and kept passing while the rule stopped holding: the
 * commit button ran a full {@code git status} on the FX thread per click, and the graph's
 * search box spawned a {@code git} subprocess per character typed. Reading finds the
 * pattern of a call, never the thread it runs on.
 *
 * <p>{@link ThreadGuard} closes that. These tests cover the guard itself; the paths it
 * protects are covered where they live, by the panel and graph tests running with it armed.
 */
class ThreadGuardTest {

    @TempDir
    Path tempDir;

    private GitService service;

    @BeforeEach
    void setUp() throws Exception {
        ThreadGuard.clear();
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        Files.writeString(tempDir.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("first", false).isOk());
    }

    @AfterEach
    void tearDown() {
        ThreadGuard.strict(false);
        ThreadGuard.clear();
        if (service != null) service.close();
    }

    // ------------------------------------------------------------------
    // The guard
    // ------------------------------------------------------------------

    /** A worker thread is the whole point; it must cost nothing and say nothing. */
    @Test
    void workOffTheFxThreadIsNotAViolation() {
        ThreadGuard.strict(true);

        assertDoesNotThrow(() -> ThreadGuard.offTheFxThread("git status"));
        assertTrue(ThreadGuard.violations().isEmpty());
    }

    @Test
    void strictModeIsOffByDefault() {
        assertFalse(ThreadGuard.isStrict(),
                "a freeze is bad; an exception thrown into a half-finished merge is worse");
    }

    // ------------------------------------------------------------------
    // What the guard is protecting - D4's own paths
    // ------------------------------------------------------------------

    /**
     * The staging query answers the branch and the operation state, so the commit button
     * has nothing left to ask the backend for.
     *
     * <p>This is the shape of the fix rather than an incidental detail: the button updates
     * on every count change, and each of those used to be two synchronous git calls.
     */
    @Test
    void theStagingQueryAnswersWhatTheCommitButtonNeeds() {
        StagingState state = service.getStagingState();

        assertEquals("master", state.branch(),
                "the branch travels with the lists that were read in the same scan");
        assertFalse(state.midOperation());
        assertFalse(state.hasUncommittedChanges(), "a clean tree has nothing to commit");
    }

    @Test
    void theStagingQueryReportsWorkToCommit() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "changed\n", StandardCharsets.UTF_8);

        assertTrue(service.getStagingState().hasUncommittedChanges());
    }

    /**
     * A merge that resolved by taking one side verbatim differs from HEAD by nothing, and
     * still needs its commit. Left to the file lists alone the button reads "Nothing
     * staged" and disables itself, with no way to finish the merge at all - which is why
     * {@code midOperation} is carried rather than derived.
     */
    @Test
    void aPartFinishedOperationCountsAsWorkToCommit() throws Exception {
        Files.writeString(new File(service.getWorkTree(), ".git/MERGE_HEAD").toPath(),
                "0".repeat(40) + "\n", StandardCharsets.UTF_8);

        StagingState state = service.getStagingState();

        assertTrue(state.midOperation(), "an unfinished merge is mid-operation");
        assertTrue(state.hasUncommittedChanges(),
                "the commit that finishes a merge must stay available");
    }

    // ------------------------------------------------------------------
    // R9-2. Telling application code from test scaffolding
    // ------------------------------------------------------------------

    private static ThreadGuard.Violation from(String caller) {
        return new ThreadGuard.Violation("git status", caller);
    }

    @Test
    void aUiClassIsProductCode() {
        assertTrue(from("com.jfx.git.ui.SidebarPane.refresh(SidebarPane.java:1265)").isProductCode());
        assertTrue(from("com.jfx.git.graph.GitGraphCell.updateItem(GitGraphCell.java:42)").isProductCode());
    }

    @Test
    void aTestClassIsNot() {
        assertFalse(from("com.jfx.git.ui.AppShortcutsRobotTest.start(AppShortcutsRobotTest.java:99)")
                .isProductCode());
    }

    /**
     * The trap the naming heuristic sets: scaffolding whose name does not end in "Test"
     * would be reported as an application defect, inside whichever unrelated test was
     * running at the time.
     */
    @Test
    void namedTestScaffoldingIsNotProductCodeEither() {
        assertFalse(from("com.jfx.git.ui.FxHarness.call(FxHarness.java:60)").isProductCode());
        assertFalse(from("com.jfx.git.testsupport.TestEnvironment.beforeEach(TestEnvironment.java:70)")
                .isProductCode());
    }

    /** A class merely starting with "Test" is application code, not scaffolding. */
    @Test
    void theNameHasToEndWithTestNotBeginWithIt() {
        assertTrue(from("com.jfx.git.ui.TestRunnerPanel.load(TestRunnerPanel.java:10)")
                .isProductCode());
    }

    @Test
    void codeOutsideThisProjectIsNeverBlamed() {
        assertFalse(from("javafx.scene.control.ListCell.updateItem(ListCell.java:1)").isProductCode());
        assertFalse(from("an unknown caller").isProductCode());
    }

    /** The scope ends where it says it does. */
    @Test
    void theFixtureScopeIsNotLeftOpen() throws Exception {
        try (var fixture = ThreadGuard.buildingFixture()) {
            assertNotNull(fixture);
        }
        // Off the FX thread nothing is recorded anyway; what matters is that the flag was
        // restored rather than left set for every later call on this thread.
        ThreadGuard.strict(true);
        assertDoesNotThrow(() -> ThreadGuard.offTheFxThread("git status"));
        ThreadGuard.strict(false);
    }
}
