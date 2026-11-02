package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** R15: what a clone brings down, and what a cancelled one says it left behind. */
class GitServiceCloneTest {

    @TempDir
    Path root;

    static {
        // Windows will not delete a memory-mapped pack file from a @TempDir.
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    /** A clone whose submodule directories were empty did not build, and nothing said why. */
    @Test
    void cloneBringsSubmodulesDownToo() throws Exception {
        File lib = root.resolve("lib").toFile();
        try (Git git = Git.init().setDirectory(lib).setInitialBranch("main").call()) {
            Files.writeString(lib.toPath().resolve("lib.txt"), "library\n");
            git.add().addFilepattern("lib.txt").call();
            git.commit().setMessage("lib").setSign(false).call();
        }
        File app = root.resolve("app").toFile();
        try (Git git = Git.init().setDirectory(app).setInitialBranch("main").call()) {
            Files.writeString(app.toPath().resolve("app.txt"), "app\n");
            git.add().addFilepattern("app.txt").call();
            git.submoduleAdd().setPath("libs/lib").setURI(lib.getAbsolutePath()).call().close();
            git.commit().setMessage("app with lib").setSign(false).call();
        }

        File clone = root.resolve("clone").toFile();
        GitResult result = GitService.cloneRepository(app.getAbsolutePath(), clone);

        assertTrue(result.isOk(), result::getMessage);
        assertTrue(new File(clone, "libs/lib/lib.txt").isFile(), "the submodule should be checked out");
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    /** The message used to promise partial data was kept when JGit had removed it. */
    @Test
    void aCancelledCloneReportsWhatIsReallyOnDisk() throws Exception {
        File gone = root.resolve("gone").toFile();
        assertTrue(GitService.cancelledCloneMessage(gone).contains("Nothing was kept"));

        File empty = root.resolve("empty").toFile();
        assertTrue(empty.mkdirs());
        assertTrue(GitService.cancelledCloneMessage(empty).contains("Nothing was kept"));

        File partial = root.resolve("partial").toFile();
        assertTrue(new File(partial, ".git").mkdirs());
        assertTrue(GitService.cancelledCloneMessage(partial).contains(partial.getAbsolutePath()));
    }
}
