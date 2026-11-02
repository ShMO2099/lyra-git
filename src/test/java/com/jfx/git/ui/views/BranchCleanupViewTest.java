package com.jfx.git.ui.views;

import com.jfx.git.core.DeletedBranch;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.testsupport.Engines;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the branch cleanup offers, and that deleting and undoing gives back each branch as
 * it was - upstream included - on each engine.
 */
@Tag("heavy")
class BranchCleanupViewTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private File remote;

    @AfterEach
    void close() {
        opened.forEach(GitBackend::close);
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    /**
     * {@code main}, published with an {@code origin/HEAD}, and around it: a branch merged
     * into it, one merged whose remote branch was deleted, one with work of its own whose
     * remote branch was deleted, one ahead that tracks nothing, one still published, and
     * {@code develop}.
     */
    private GitBackend fixture(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("repo").toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            for (String content : List.of("one\n", "one\ntwo\n")) {
                Files.writeString(dir.toPath().resolve("a.txt"), content);
                git.add().addFilepattern("a.txt").call();
                git.commit().setMessage("main " + content.length()).call();
            }
        }
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);

        remote = root.resolve("remote.git").toFile();
        Git.init().setDirectory(remote).setBare(true).call().close();
        assertTrue(backend.addRemote("origin", remote.getAbsolutePath()).isOk());
        assertTrue(new RepositoryCommands(backend).push("origin", false, false, true).isOk());
        Files.writeString(new File(backend.getDirectory(), "refs/remotes/origin/HEAD").toPath(),
                "ref: refs/remotes/origin/main\n");

        assertTrue(backend.createBranch("merged", "main~1", false).isOk());
        assertTrue(backend.createBranch("develop", "main~1", false).isOk());
        publish(backend, "gone-merged", null);
        publish(backend, "gone-unmerged", "b.txt");
        publish(backend, "published", null);
        assertTrue(backend.createBranch("ahead", null, true).isOk());
        commitFile(backend, "c.txt");
        assertTrue(backend.checkoutBranch("main").isOk());
        forget(backend, "gone-merged");
        forget(backend, "gone-unmerged");
        return backend;
    }

    private static void publish(GitBackend backend, String branch, String ownFile) throws Exception {
        assertTrue(backend.createBranch(branch, null, true).isOk());
        if (ownFile != null) commitFile(backend, ownFile);
        GitResult pushed = new RepositoryCommands(backend).push(null, false, false, false);
        assertTrue(pushed.isOk(), pushed::getMessage);
        assertTrue(backend.checkoutBranch("main").isOk());
    }

    private static void commitFile(GitBackend backend, String file) throws Exception {
        Files.writeString(new File(backend.getWorkTree(), file).toPath(), file + "\n");
        assertTrue(backend.stageFile(file).isOk());
        assertTrue(backend.commit("add " + file, false).isOk());
    }

    /** Deletes the branch on the remote and this clone's record of it - what a prune leaves. */
    private void forget(GitBackend backend, String branch) throws Exception {
        try (Git bare = Git.open(remote)) {
            var update = bare.getRepository().updateRef("refs/heads/" + branch);
            update.setForceUpdate(true);
            update.delete();
        }
        try (Git git = Git.open(backend.getWorkTree())) {
            var update = git.getRepository().updateRef("refs/remotes/origin/" + branch);
            update.setForceUpdate(true);
            update.delete();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void offersMergedAndGoneBranchesButNeverTheOnesToKeep(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        BranchCleanupView.Scan scan = BranchCleanupView.scan(backend);

        assertEquals("origin/main", scan.base(), name + ": measured against origin/HEAD's branch");
        Map<String, BranchCleanupView.Found> found = scan.branches().stream()
                .collect(Collectors.toMap(BranchCleanupView.Found::branch, f -> f));
        assertEquals(Set.of("merged", "gone-merged", "gone-unmerged", "published"), found.keySet(),
                name + ": not main, not develop, not the branch ahead with no upstream");
        assertTrue(found.get("merged").merged());
        assertFalse(found.get("merged").gone());
        assertTrue(found.get("gone-merged").merged() && found.get("gone-merged").gone());
        assertTrue(found.get("gone-unmerged").gone());
        assertFalse(found.get("gone-unmerged").merged());
        assertTrue(found.get("published").onRemote(), name + ": its remote branch is still there");
        assertFalse(found.get("gone-merged").onRemote(), name + ": nothing left on the remote to delete");
        assertEquals("Upstream gone, not merged into origin/main", found.get("gone-unmerged").reason("origin/main"));
        assertEquals("Merged into origin/main", found.get("merged").reason("origin/main"));
        assertTrue(found.get("gone-unmerged").subject().startsWith("add b.txt"), found.get("gone-unmerged").subject());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void deletingThenUndoingRestoresEachBranchWithItsUpstream(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        List<BranchCleanupView.Found> chosen = BranchCleanupView.scan(backend).branches().stream()
                .filter(f -> !f.branch().equals("merged")).toList();
        Map<String, String> tips = new java.util.HashMap<>();
        backend.getLocalBranches().forEach(ref -> tips.put(ref.shortName(), ref.targetHash()));

        var outcome = BranchCleanupView.delete(backend, chosen, Set.of("published"));

        assertEquals(List.of(), outcome.failures(), name);
        assertEquals(List.of("origin/published"), outcome.deletedOnRemote());
        List<String> left = backend.getLocalBranches().stream().map(r -> r.shortName()).sorted().toList();
        assertEquals(List.of("ahead", "develop", "main", "merged"), left, name);
        try (Git bare = Git.open(remote)) {
            assertNull(bare.getRepository().exactRef("refs/heads/published"), name + ": gone from the remote");
        }

        GitResult restored = BranchCleanupView.restore(backend, outcome.deleted());

        assertTrue(restored.isOk(), () -> name + ": " + restored.getMessage());
        for (String branch : List.of("gone-merged", "gone-unmerged", "published")) {
            String tip = backend.getLocalBranches().stream().filter(r -> r.shortName().equals(branch))
                    .map(r -> r.targetHash()).findFirst().orElse(null);
            assertEquals(tips.get(branch), tip, name + ": " + branch + " is back where it was");
            assertEquals("origin/" + branch, backend.getUpstream(branch), name + ": and tracks what it tracked");
        }
    }

    /** The sidebar's own delete uses the same capture; a branch that tracked nothing comes back tracking nothing. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBranchWithoutAnUpstreamComesBackWithout(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        DeletedBranch before = DeletedBranch.capture(backend, "ahead");
        assertFalse(before.hadUpstream());
        assertTrue(backend.deleteBranch("ahead", true).isOk());

        assertTrue(before.restore(backend).isOk());

        assertNull(backend.getUpstream("ahead"), name);
        assertNull(DeletedBranch.capture(backend, "no-such-branch"));
    }
}
