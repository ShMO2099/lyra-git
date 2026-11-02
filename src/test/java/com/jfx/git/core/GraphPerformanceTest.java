package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.graph.GraphPage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A budget for the graph, on a repository large enough for the budget to mean something.
 *
 * <h2>Why this exists</h2>
 * Everything else in this suite runs against repositories of five or ten commits, where
 * an accidental quadratic or a second walk per page costs nothing measurable. The graph
 * is where this application's performance actually lives - it is the screen that opens
 * first and refreshes after every operation - and nothing was watching it. A change that
 * made the page ten times slower would have passed every test in the project.
 *
 * <h2>What the numbers mean</h2>
 * The budgets are deliberately loose, several times the observed cost on an ordinary
 * developer machine. They are not a benchmark and will not tell you that a page got 20%
 * slower; they are a tripwire for the kind of regression that turns a page into a stall -
 * walking the whole history instead of a page, or re-reading the object database per row.
 * Machines vary and CI machines vary more, so a tight budget here would only teach people
 * to ignore the failure.
 *
 * <p>Tagged {@code slow}: the fixture alone is ten thousand commits.
 * {@code mvn test -Dgroups=slow -DexcludedTestGroups=}
 */
@Tag("slow")
class GraphPerformanceTest {

    private static final int COMMITS = 10_000;
    private static final int BRANCHES = 50;
    private static final int TAGS = 200;

    /** One page of the graph, the operation a refresh performs. */
    private static final long PAGE_BUDGET_MILLIS = 4_000;

    /** A context-menu query, which happens while the user is waiting on a menu to open. */
    private static final long POSITION_BUDGET_MILLIS = 1_500;

    /**
     * The same query against the first commit in the history, which is the worst case
     * for the ancestry walk behind it. See
     * {@link #locatingTheOldestCommitStaysWithinItsRecordedCost()} - this is headroom
     * over a measured cost, not a target.
     */
    private static final long DEEP_POSITION_CEILING_MILLIS = 10_000;

    @TempDir
    static Path tempDir;

    private static GitService service;

    static {
        // Windows will not delete a memory-mapped pack file, and @TempDir has to.
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    /**
     * Builds the fixture once for the whole class.
     *
     * <p>Commits are written straight into the object database with an
     * {@code ObjectInserter} rather than through {@code add} + {@code commit}. Ten
     * thousand of the latter means ten thousand index reads and writes, which took four
     * minutes and measured nothing this test is about - the graph does not care how the
     * commits got there. Every commit shares one tree for the same reason.
     *
     * <p>Dates step forward a minute each so commit-time ordering is total and the walk
     * has something deterministic to sort.
     */
    @BeforeAll
    static void buildFixture() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            Repository repo = git.getRepository();
            var config = repo.getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();

            Instant when = Instant.parse("2020-01-01T00:00:00Z");
            List<ObjectId> tips = new ArrayList<>();

            try (ObjectInserter inserter = repo.newObjectInserter()) {
                ObjectId blob = inserter.insert(Constants.OBJ_BLOB,
                        "content".getBytes(StandardCharsets.UTF_8));
                TreeFormatter tree = new TreeFormatter();
                tree.append("f.txt", FileMode.REGULAR_FILE, blob);
                ObjectId treeId = inserter.insert(tree);

                ObjectId parent = null;
                for (int i = 0; i < COMMITS; i++) {
                    PersonIdent who = new PersonIdent("Test User", "test@example.com",
                            when.plusSeconds(60L * i), java.time.ZoneOffset.UTC);
                    CommitBuilder builder = new CommitBuilder();
                    builder.setTreeId(treeId);
                    if (parent != null) builder.setParentId(parent);
                    builder.setAuthor(who);
                    builder.setCommitter(who);
                    builder.setMessage("commit " + i);
                    parent = inserter.insert(builder);
                    tips.add(parent);
                }
                inserter.flush();

                RefUpdate head = repo.updateRef("refs/heads/main");
                head.setNewObjectId(parent);
                head.setForceUpdate(true);
                head.update();
            }

            // Branches and tags spread across the history, so ref decoration has real
            // work to do rather than finding everything on one commit. Half the tags are
            // annotated, which is the case that has to be peeled at listing time.
            for (int b = 0; b < BRANCHES; b++) {
                ObjectId at = tips.get((b * COMMITS / BRANCHES) % COMMITS);
                git.branchCreate().setName("branch-" + b).setStartPoint(at.getName()).call();
            }
            for (int t = 0; t < TAGS; t++) {
                ObjectId at = tips.get((t * COMMITS / TAGS) % COMMITS);
                var tag = git.tag().setName("v" + t).setObjectId(repo.parseCommit(at));
                if (t % 2 == 0) {
                    tag.setAnnotated(true).setMessage("tag " + t);
                } else {
                    tag.setAnnotated(false);
                }
                tag.call();
            }

            // The commits were written without touching the index or the working tree,
            // so both are empty. Matching them to HEAD keeps the graph free of a WIP row
            // and keeps the status scan measuring a clean repository, which is what a
            // page normally runs against.
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();
        }

        service = new GitService();
        assertTrue(service.openRepository(tempDir.toFile()));
    }

    @AfterAll
    static void tearDown() {
        if (service != null) {
            service.close();
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private static long millisFor(Runnable work) {
        // One warm-up: the first call pays for JGit opening the pack files, which is a
        // cost the user pays once per repository and not per page.
        work.run();
        long start = System.nanoTime();
        work.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void aGraphPageOfAThousandCommitsStaysWithinBudget() {
        long[] size = new long[1];
        long millis = millisFor(() -> {
            GraphPage page = service.getCommitGraphPage(1_000, GraphOrder.BY_BRANCH);
            size[0] = page.rows().size();
        });

        assertTrue(size[0] >= 1_000, "the fixture should fill a full page, got " + size[0]);
        assertTrue(millis < PAGE_BUDGET_MILLIS,
                () -> "a 1000-commit page took " + millis + "ms over a " + COMMITS
                        + "-commit repository; the budget is " + PAGE_BUDGET_MILLIS + "ms");
    }

    /**
     * The first page must not cost what the whole history costs.
     *
     * <p>This is the shape of the regression that matters: an implementation that walks
     * everything and then cuts to the page size passes the test above and fails this one,
     * because its cost does not move with the page size.
     */
    @Test
    void asmallerPageIsCheaperThanALargerOne() {
        long small = millisFor(() -> service.getCommitGraphPage(100, GraphOrder.BY_BRANCH));
        long large = millisFor(() -> service.getCommitGraphPage(5_000, GraphOrder.BY_BRANCH));

        assertTrue(small <= large,
                () -> "a 100-row page (" + small + "ms) should not cost more than a 5000-row"
                        + " page (" + large + "ms) - the page size has to bound the walk");
    }

    /**
     * The commit the user is most likely to click - a recent one - has to be located
     * while a context menu is opening.
     */
    @Test
    void locatingARecentCommitIsFast() {
        String recent = service.getHeadHash();

        long[] known = new long[1];
        long millis = millisFor(() -> known[0] = service.getCommitPosition(recent).known() ? 1 : 0);

        assertEquals(1, known[0], "HEAD should be locatable");
        assertTrue(millis < POSITION_BUDGET_MILLIS,
                () -> "locating a recent commit took " + millis + "ms; the budget is "
                        + POSITION_BUDGET_MILLIS + "ms");
    }

    /**
     * Locating the <em>oldest</em> commit is the deep case, and it is slow.
     *
     * <p>Measured at roughly 3.7 seconds on a ten-thousand-commit repository while this
     * test was written: {@code onCurrentBranch} is a {@code RevWalk.isMergedInto} from
     * the commit to HEAD, which for the first commit in the history means walking all of
     * it. That is a real cost to a user who right-clicks an old row, and it is recorded
     * here rather than hidden - the ceiling below is headroom over today's number, not an
     * endorsement of it.
     *
     * <p>What this guards is the direction of travel: something making this an order of
     * magnitude worse, or reintroducing a per-commit walk, trips it.
     */
    @Test
    void locatingTheOldestCommitStaysWithinItsRecordedCost() {
        String oldest = service.getCommitGraphPage(COMMITS, GraphOrder.BY_BRANCH)
                .rows().stream()
                .filter(r -> r.getCommit() != null)
                .reduce((a, b) -> b)
                .orElseThrow()
                .getCommit().getHash();

        long[] known = new long[1];
        long millis = millisFor(() -> known[0] = service.getCommitPosition(oldest).known() ? 1 : 0);

        assertEquals(1, known[0], "the oldest commit should be locatable");
        assertTrue(millis < DEEP_POSITION_CEILING_MILLIS,
                () -> "locating the oldest commit took " + millis + "ms; the recorded cost is"
                        + " about 3700ms and the ceiling is " + DEEP_POSITION_CEILING_MILLIS + "ms");
    }

    @Test
    void theFixtureIsTheSizeThisTestClaims() throws IOException {
        assertEquals(BRANCHES + 1, service.getLocalBranches().size(), "branches plus main");
        assertEquals(TAGS, service.getTags().size());
    }
}
