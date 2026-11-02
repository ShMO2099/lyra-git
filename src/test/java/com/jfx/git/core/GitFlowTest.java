package com.jfx.git.core;

import com.jfx.git.core.GitFlow.Config;
import com.jfx.git.core.GitFlow.Kind;
import com.jfx.git.model.RefInfo;
import com.jfx.git.testsupport.Engines;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git Flow, start to finish, on every engine.
 *
 * <p>{@link GitFlow} is made of ordinary operations, so what could differ between
 * engines is not the model but the pieces: whether a merge of something already merged
 * is a success, whether a tag made "on the production branch" lands there. Each path is
 * therefore run against all of them, and checked against the history it leaves.
 */
@Tag("heavy")
class GitFlowTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private int count;

    static {
        var windows = new org.eclipse.jgit.storage.file.WindowCacheConfig();
        windows.setPackedGitMMAP(false);
        windows.install();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    @AfterEach
    void tearDown() {
        for (GitBackend backend : opened) {
            try {
                backend.close();
            } catch (Exception ignored) {
                // A test that already failed should report its own reason.
            }
        }
        opened.clear();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    /** One commit on {@code main}, opened by the engine under test. */
    private GitBackend repository(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("repo" + (count++)).toFile();
        assertTrue(dir.mkdirs());
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(dir.toPath().resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
        }
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }

    private static GitFlow initialised(GitBackend backend) {
        GitFlow flow = new GitFlow(backend);
        GitResult init = flow.init(new Config("main", "develop", "feature/", "release/", "hotfix/", "v"));
        assertTrue(init.isOk(), init::getMessage);
        return flow;
    }

    private static void commit(GitBackend backend, String file, String content, String message) throws Exception {
        Files.writeString(new File(backend.getWorkTree(), file).toPath(), content);
        assertTrue(backend.stageFile(file).isOk());
        assertTrue(backend.commit(message, false).isOk());
    }

    private static List<String> branches(GitBackend backend) {
        return backend.getLocalBranches().stream().map(RefInfo::shortName).sorted().toList();
    }

    private static RevCommit tip(GitBackend backend, String ref) throws Exception {
        try (Git git = Git.open(backend.getWorkTree()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(git.getRepository().resolve(ref));
            walk.parseBody(commit);
            for (RevCommit parent : commit.getParents()) walk.parseBody(parent);
            return commit;
        }
    }

    private static String read(GitBackend backend, String file) throws Exception {
        return Files.readString(new File(backend.getWorkTree(), file).toPath());
    }

    // ------------------------------------------------------------------
    // Setting up
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void settingUpRecordsTheModelAndCreatesDevelop(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = new GitFlow(backend);
        assertNull(flow.config(), name + ": a repository is not a git-flow one until it is set up");
        assertEquals(List.of(), flow.flowBranches());

        GitResult init = flow.init(new Config("main", "develop", "feature/", "release/", "hotfix/", "v"));
        assertTrue(init.isOk(), () -> name + ": " + init.getMessage());

        assertEquals(new Config("main", "develop", "feature/", "release/", "hotfix/", "v"), flow.config());
        assertEquals(List.of("develop", "main"), branches(backend), name + ": develop is made from main");
        assertEquals("main", backend.getCurrentBranch(), name + ": and nothing is checked out");
        assertEquals(tip(backend, "main").getName(), tip(backend, "develop").getName());
        // The keys the git-flow command line reads, so either tool can carry on.
        assertEquals("develop", backend.getLocalConfig("gitflow.branch.develop"));
        assertEquals("main", backend.getLocalConfig("gitflow.branch.master"));
        assertEquals("feature/", backend.getLocalConfig("gitflow.prefix.feature"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void settingUpRefusesAModelThatCannotWork(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = new GitFlow(backend);

        assertFalse(flow.init(new Config("trunk", "develop", "feature/", "release/", "hotfix/", "")).isOk(),
                name + ": there is no branch called trunk to release from");
        assertFalse(flow.init(new Config("main", "main", "feature/", "release/", "hotfix/", "")).isOk(),
                name + ": one branch cannot be both");
        assertFalse(flow.init(new Config("main", "develop", "", "release/", "hotfix/", "")).isOk(),
                name + ": without a prefix a feature is any branch at all");
        assertNull(flow.config(), name + ": a refused setup records nothing");
        assertEquals(List.of("main"), branches(backend));
    }

    // ------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aFeatureStartsFromDevelopAndAHotfixFromProduction(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = initialised(backend);
        // Develop moves on from main, so the two starting points can be told apart.
        assertTrue(backend.checkoutBranch("develop").isOk());
        commit(backend, "d.txt", "develop\n", "work on develop");
        String develop = tip(backend, "develop").getName();
        String main = tip(backend, "main").getName();

        GitResult feature = flow.start(Kind.FEATURE, "login");
        assertTrue(feature.isOk(), () -> name + ": " + feature.getMessage());
        assertEquals("feature/login", backend.getCurrentBranch(), name + ": the new branch is checked out");
        assertEquals(develop, tip(backend, "feature/login").getName());

        GitResult hotfix = flow.start(Kind.HOTFIX, "1.0.1");
        assertTrue(hotfix.isOk(), () -> name + ": " + hotfix.getMessage());
        assertEquals(main, tip(backend, "hotfix/1.0.1").getName(),
                name + ": a hotfix repairs what is released, without what develop has gathered since");

        assertEquals(List.of("feature/login", "hotfix/1.0.1"), flow.flowBranches().stream().sorted().toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void startingRefusesABadNameADuplicateAndARepositoryNotSetUp(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = repository(factory);
        assertFalse(new GitFlow(backend).start(Kind.FEATURE, "login").isOk(), name + ": not set up yet");

        GitFlow flow = initialised(backend);
        assertFalse(flow.start(Kind.FEATURE, "  ").isOk());
        assertFalse(flow.start(Kind.FEATURE, "two words").isOk());
        assertTrue(flow.start(Kind.FEATURE, "login").isOk());
        assertFalse(flow.start(Kind.FEATURE, "login").isOk(), name + ": that feature is already under way");
    }

    // ------------------------------------------------------------------
    // Finishing
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void finishingAFeatureMergesItIntoDevelopAndDeletesIt(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = initialised(backend);
        assertTrue(flow.start(Kind.FEATURE, "login").isOk());
        commit(backend, "login.txt", "login\n", "add login");
        assertEquals(List.of("Merge feature/login into develop", "Delete feature/login"),
                flow.finishSteps("feature/login"));

        GitResult finished = flow.finish("feature/login", null);
        assertTrue(finished.isOk(), () -> name + ": " + finished.getMessage());

        assertEquals("develop", backend.getCurrentBranch());
        assertEquals(List.of("develop", "main"), branches(backend), name + ": the feature branch is gone");
        assertEquals("login\n", read(backend, "login.txt"));
        assertEquals(2, tip(backend, "develop").getParentCount(),
                name + ": a merge commit, so the history shows that a feature happened");
        assertEquals(0, tip(backend, "main").getParentCount(),
                name + ": main is still the first commit; a feature does not touch production");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void finishingAReleaseMergesBothWaysAndTagsProduction(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = initialised(backend);
        assertTrue(flow.start(Kind.RELEASE, "1.0").isOk());
        commit(backend, "version.txt", "1.0\n", "bump version");
        assertEquals(List.of("Merge release/1.0 into main", "Tag main as v1.0",
                        "Merge release/1.0 into develop", "Delete release/1.0"),
                flow.finishSteps("release/1.0"));

        GitResult finished = flow.finish("release/1.0", "");
        assertTrue(finished.isOk(), () -> name + ": " + finished.getMessage());

        assertEquals(List.of("develop", "main"), branches(backend));
        assertEquals(2, tip(backend, "main").getParentCount(), name + ": merged into production");
        assertEquals(2, tip(backend, "develop").getParentCount(), name + ": and back into develop");
        assertEquals(tip(backend, "main").getName(), tip(backend, "v1.0^{commit}").getName(),
                name + ": the tag is on the production branch's merge");
        assertTrue(backend.getTags().stream().anyMatch(t -> t.shortName().equals("v1.0")));
        assertTrue(backend.checkoutBranch("main").isOk());
        assertEquals("1.0\n", read(backend, "version.txt"));
    }

    /**
     * A finish that stops on a conflict is carried on by finishing again, not started
     * over: the merge that was resolved is not repeated, and the branch is deleted only
     * once everything has landed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aFinishThatStopsOnAConflictIsCarriedOnByFinishingAgain(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = initialised(backend);
        assertTrue(flow.start(Kind.FEATURE, "wording").isOk());
        commit(backend, "a.txt", "the feature's wording\n", "reword in the feature");
        assertTrue(backend.checkoutBranch("develop").isOk());
        commit(backend, "a.txt", "develop's wording\n", "reword on develop");

        GitResult stopped = flow.finish("feature/wording", null);
        assertTrue(stopped.isConflict(), () -> name + ": expected a conflict, got " + stopped.getMessage());
        assertTrue(stopped.getMessage().contains("finish feature/wording again"), stopped.getMessage());
        assertTrue(branches(backend).contains("feature/wording"),
                name + ": nothing is deleted while its work has not landed");

        // Resolved the way a person would: decide, stage, commit the merge.
        Files.writeString(new File(backend.getWorkTree(), "a.txt").toPath(), "agreed wording\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        assertTrue(backend.commit("Merge feature/wording into develop", false).isOk());

        GitResult finished = flow.finish("feature/wording", null);
        assertTrue(finished.isOk(), () -> name + ": " + finished.getMessage());
        assertEquals(List.of("develop", "main"), branches(backend));
        assertEquals("agreed wording\n", read(backend, "a.txt"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void onlyAFlowBranchCanBeFinished(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = initialised(backend);

        assertFalse(flow.finish("develop", null).isOk(), name + ": develop is not a feature");
        assertFalse(flow.finish("feature/never-started", null).isOk());
        assertEquals(List.of(), flow.finishSteps("main"));
    }

    // ------------------------------------------------------------------
    // Finishing over local changes
    // ------------------------------------------------------------------

    private static final String FIVE_LINES = "line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String FEATURE_LINES = "feature line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String FEATURE_WITH_LOCAL_EDIT = "feature line 1\nline 2\nline 3\nline 4\nlocal line 5\n";
    private static final String SCRATCH = "untracked scratch\n";

    /**
     * Set up, with {@code shared.txt} on develop and {@code feature/login} checked out,
     * one commit ahead with line 1 of it changed: checking out develop over an edit to
     * that file is refused, because the file differs between the two.
     */
    private GitFlow featureEditingShared(GitBackend backend) throws Exception {
        GitFlow flow = initialised(backend);
        assertTrue(backend.checkoutBranch("develop").isOk());
        commit(backend, "shared.txt", FIVE_LINES, "shared on develop");
        assertTrue(flow.start(Kind.FEATURE, "login").isOk());
        commit(backend, "shared.txt", FEATURE_LINES, "feature edit");
        return flow;
    }

    /**
     * Finishing checks out the branch it merges into, which local changes would stop part-
     * way. The finish auto-stashes them before its first step - untracked files too - and
     * puts them back after its last, so they end up on develop with the feature merged
     * under them.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void finishingOverLocalChangesStashesThemFirstAndPutsThemBackOnTop(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = featureEditingShared(backend);
        String developTip = tip(backend, "develop").getName();
        String featureTip = tip(backend, "feature/login").getName();
        Files.writeString(new File(backend.getWorkTree(), "shared.txt").toPath(), FEATURE_WITH_LOCAL_EDIT);
        Files.writeString(new File(backend.getWorkTree(), "scratch.txt").toPath(), SCRATCH);

        GitResult finished = flow.finish("feature/login", null);

        assertTrue(finished.isOk(), () -> name + ": " + finished.getMessage());
        assertTrue(finished.getMessage().contains("your changes were re-applied"), finished::getMessage);
        assertEquals("develop", backend.getCurrentBranch(), name + ": finishing ends on develop");
        RevCommit merge = tip(backend, "develop");
        assertEquals(2, merge.getParentCount(), name + ": a feature is finished with a merge commit");
        assertEquals(developTip, merge.getParent(0).getName());
        assertEquals(featureTip, merge.getParent(1).getName());
        assertEquals(List.of("develop", "main"), branches(backend), name + ": the feature is deleted");
        assertEquals(FEATURE_WITH_LOCAL_EDIT, read(backend, "shared.txt"),
                name + ": the local edit is back, on top of the finished feature");
        assertEquals(SCRATCH, read(backend, "scratch.txt"), name + ": the untracked file is back byte for byte");
        assertEquals(List.of(), backend.getStashEntries(), name + ": no stash is left behind");
    }

    /** A clean tree has nothing to stash, and finishing it never touches the stash. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void finishingACleanTreeMakesNoStash(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = featureEditingShared(backend);

        GitResult finished = flow.finish("feature/login", null);

        assertTrue(finished.isOk(), () -> name + ": " + finished.getMessage());
        assertEquals(FEATURE_LINES, read(backend, "shared.txt"));
        File gitDir = backend.getDirectory();
        assertFalse(new File(gitDir, "refs/stash").exists(), name + ": no stash was ever made");
        assertFalse(new File(gitDir, "logs/refs/stash").exists(), name + ": not even one made and dropped");
    }

    /** What the confirmation shows: the steps, and whether local changes go into a stash first. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theFinishPlanSaysWhetherLocalChangesAreStashedFirst(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = featureEditingShared(backend);
        List<String> steps = List.of("Merge feature/login into develop", "Delete feature/login");

        assertEquals(new GitFlow.FinishPlan(steps, false), flow.finishPlan("feature/login"), name + ": clean");

        Files.writeString(new File(backend.getWorkTree(), "scratch.txt").toPath(), SCRATCH);
        assertEquals(new GitFlow.FinishPlan(steps, true), flow.finishPlan("feature/login"),
                name + ": an untracked file is stashed too");

        Files.delete(new File(backend.getWorkTree(), "scratch.txt").toPath());
        Files.writeString(new File(backend.getWorkTree(), "shared.txt").toPath(), FEATURE_WITH_LOCAL_EDIT);
        assertEquals(new GitFlow.FinishPlan(steps, true), flow.finishPlan("feature/login"), name + ": an edit");

        assertTrue(backend.stageFile("shared.txt").isOk());
        assertEquals(new GitFlow.FinishPlan(steps, true), flow.finishPlan("feature/login"), name + ": staged");

        assertEquals(new GitFlow.FinishPlan(List.of(), false), flow.finishPlan("develop"),
                name + ": develop is not a flow branch, so there is nothing to finish or stash");
    }

    /**
     * A finish over local changes that stops on a conflict keeps them in the stash rather
     * than popping them into the half-done merge, and says when to pop them: once finishing
     * is done. Finishing again with the merge committed does not pop a stash it did not make.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aFinishOverLocalChangesThatStopsOnAConflictKeepsThemInTheStash(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = repository(factory);
        GitFlow flow = initialised(backend);
        assertTrue(flow.start(Kind.FEATURE, "wording").isOk());
        commit(backend, "a.txt", "the feature's wording\n", "reword in the feature");
        assertTrue(backend.checkoutBranch("develop").isOk());
        commit(backend, "a.txt", "develop's wording\n", "reword on develop");
        assertTrue(backend.checkoutBranch("feature/wording").isOk());
        Files.writeString(new File(backend.getWorkTree(), "scratch.txt").toPath(), SCRATCH);

        GitResult stopped = flow.finish("feature/wording", null);

        assertTrue(stopped.isConflict(), () -> name + ": expected a conflict, got " + stopped.getMessage());
        assertTrue(stopped.getMessage().contains(AutoStash.keptInStash(AutoStash.Operation.FINISH)),
                stopped::getMessage);
        List<com.jfx.git.model.StashEntry> kept = backend.getStashEntries();
        assertEquals(1, kept.size(), name + ": the changes wait in the stash");
        assertTrue(AutoStash.isAutoStash(kept.get(0)), () -> name + ": " + kept.get(0).message());
        assertFalse(new File(backend.getWorkTree(), "scratch.txt").exists(),
                name + ": the untracked file went into the stash, not into the merge");

        Files.writeString(new File(backend.getWorkTree(), "a.txt").toPath(), "agreed wording\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        assertTrue(backend.commit("Merge feature/wording into develop", false).isOk());
        GitResult finished = flow.finish("feature/wording", null);

        assertTrue(finished.isOk(), () -> name + ": " + finished.getMessage());
        assertEquals(List.of("develop", "main"), branches(backend));
        assertEquals(kept.get(0).hash(), backend.getStashEntries().get(0).hash(),
                name + ": the stash is still there for the user to pop");
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    @Test
    void aBranchsKindNameAndTagComeFromItsPrefix() {
        Config config = new Config("main", "develop", "feature/", "release/", "hotfix/", "v");

        assertEquals(Kind.FEATURE, GitFlow.kindOf(config, "feature/login"));
        assertEquals(Kind.RELEASE, GitFlow.kindOf(config, "release/1.2"));
        assertEquals(Kind.HOTFIX, GitFlow.kindOf(config, "hotfix/1.2.1"));
        assertNull(GitFlow.kindOf(config, "develop"));
        assertNull(GitFlow.kindOf(config, "feature/"), "a prefix alone names nothing");
        assertNull(GitFlow.kindOf(null, "feature/login"));

        assertEquals("login", GitFlow.nameOf(config, "feature/login"));
        assertEquals("v1.2", GitFlow.tagFor(config, "release/1.2"));
        assertEquals("1.2", GitFlow.tagFor(Config.defaults("main"), "release/1.2"),
                "git-flow's own default is no prefix on the tag");
        assertNotNull(Config.defaults("main"));
    }
}
