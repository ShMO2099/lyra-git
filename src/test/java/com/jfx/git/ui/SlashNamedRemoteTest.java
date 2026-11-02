package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.GraphOrder;

import javafx.scene.control.TreeItem;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A remote whose own name has a slash in it - {@code team/core} - is read one way by the
 * graph and the sidebar alike: {@code team/core/topic} is the branch {@code topic} on the
 * remote {@code team/core}.
 *
 * <p>It used to be read two ways. The sidebar took the longest configured remote name the
 * ref starts with ({@code team/core}, branch {@code topic}); the graph took the first path
 * segment ({@code team}, branch {@code core/topic}), so it seeded the lane's colour from a
 * branch called {@code core/topic} - not like {@code topic}, as it does for
 * {@code origin/topic} - while the sidebar nested the remote as a folder {@code team}
 * holding a folder {@code core}.
 */
class SlashNamedRemoteTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();

    static {
        // Windows will not delete a memory-mapped pack file under @TempDir.
        var config = new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @AfterEach
    void tearDown() {
        for (GitBackend backend : opened) {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The test's own failure is the one worth reporting.
            }
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    /**
     * The graph seeds {@code team/core/topic}'s lane from {@code topic}, as it seeds
     * {@code origin/topic}'s: the branch part is what follows the remote the sidebar files
     * it under, not what follows the first slash.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void theGraphReadsASlashNamedRemoteAsTheSidebarDoes(String name, Supplier<GitBackend> factory)
            throws Exception {
        File dir = root.resolve("repo").toFile();
        String tip;
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("remote", "team/core", "url", root.resolve("elsewhere").toString());
            config.setString("remote", "team/core", "fetch", "+refs/heads/*:refs/remotes/team/core/*");
            config.save();
            Files.writeString(dir.toPath().resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            RevCommit commit = git.commit().setMessage("first").call();
            tip = commit.getName();
            // The commit's only name is the remote branch: HEAD detached on it, main gone.
            setRef(git, "refs/remotes/team/core/topic", commit);
            git.checkout().setName(tip).call();
            deleteRef(git, "refs/heads/main");
        }

        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        int remoteColour = colourOf(backend, tip);

        try (Git git = Git.open(dir)) {
            setRef(git, "refs/heads/topic", git.getRepository().parseCommit(ObjectId.fromString(tip)));
        }
        int localColour = colourOf(backend, tip);

        assertEquals(localColour, remoteColour,
                name + ": team/core/topic is drawn like topic, the branch the sidebar lists under team/core");
    }

    /** The sidebar lists the remote {@code team/core} as one folder with its branches beneath. */
    @Test
    void theSidebarFilesASlashNamedRemoteAsOneFolder() {
        TreeItem<BranchTree.Node> tree = BranchTree.build(List.of("team/core/topic"), List.of("team/core"),
                path -> true);

        assertEquals(1, tree.getChildren().size());
        TreeItem<BranchTree.Node> remote = tree.getChildren().get(0);
        assertEquals("team/core", remote.getValue().label(), "the remote is one folder, not team then core");
        assertEquals("team/core", BranchTree.pathOf(remote));
        assertEquals(1, remote.getChildren().size());
        BranchTree.Node branch = remote.getChildren().get(0).getValue();
        assertEquals("topic", branch.label());
        assertEquals("team/core/topic", branch.fullName());
    }

    private static int colourOf(GitBackend backend, String hash) {
        for (GitGraphRow row : backend.getCommitGraphPage(10, GraphOrder.current()).rows()) {
            if (row.getCommit() != null && !row.isWipNode() && hash.equals(row.getCommit().getHash())) {
                return row.getCommit().getColorIndex();
            }
        }
        throw new AssertionError("the commit is not on the page");
    }

    private static void setRef(Git git, String name, RevCommit commit) throws Exception {
        RefUpdate update = git.getRepository().updateRef(name);
        update.setNewObjectId(commit);
        update.setForceUpdate(true);
        RefUpdate.Result result = update.update();
        assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                || result == RefUpdate.Result.NO_CHANGE, () -> name + ": " + result);
    }

    private static void deleteRef(Git git, String name) throws Exception {
        RefUpdate update = git.getRepository().updateRef(name);
        update.setForceUpdate(true);
        update.delete();
    }
}
