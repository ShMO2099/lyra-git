package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import com.jfx.git.testsupport.Engines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Top-level sparse-checkout choices come from a commit tree, not its last diff. */
class TopLevelDirectoriesAtCommitTest {

    @TempDir
    Path root;

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void includesTrackedDirectoriesUnchangedByHead(
            String name, Supplier<GitBackend> factory) throws Exception {
        Path repository = root.resolve(name.replace(' ', '-'));
        Files.createDirectories(repository);
        String initialHead;
        try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Sparse Test");
            config.setString("user", null, "email", "sparse@example.test");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();

            write(repository, "assets/pixel.png", "blue\n");
            write(repository, "docs/guide.md", "guide\n");
            write(repository, "src/Main.java", "class Main {}\n");
            write(repository, "README.md", "root file\n");
            git.add().addFilepattern(".").call();
            initialHead = git.commit().setMessage("all tracked folders").setSign(false).call().getName();

            write(repository, "assets/pixel.png", "orange\n");
            git.add().addFilepattern("assets/pixel.png").call();
            git.commit().setMessage("change assets only").setSign(false).call();
        }

        GitBackend backend = factory.get();
        try {
            assertTrue(backend.openRepository(repository.toFile()), name);
            assertEquals(List.of("assets", "docs", "src"),
                    backend.getTopLevelDirectoriesAtCommit("HEAD"), name + ": current tree");
            assertEquals(List.of("assets", "docs", "src"),
                    backend.getTopLevelDirectoriesAtCommit(initialHead), name + ": pinned tree");
            assertEquals(List.of(), backend.getTopLevelDirectoriesAtCommit("missing-commit"),
                    name + ": an unresolved revision has no tree entries");
        } finally {
            backend.close();
        }
    }

    private static void write(Path repository, String relative, String content) throws Exception {
        Path path = repository.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
