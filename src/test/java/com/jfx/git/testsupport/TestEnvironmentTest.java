package com.jfx.git.testsupport;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What {@link TestEnvironment} sets up for every test, checked from inside one.
 *
 * <p>JGit's automatic gc after a merge, a rebase or a fetch has to run on the calling
 * thread, so it is over - {@code gc.log.lock} and all - before {@code @TempDir} starts
 * deleting. Without it the failure is a cleanup error on some unrelated test, now and then,
 * and so far only on Linux - which is why the setting itself is pinned here.
 */
class TestEnvironmentTest {

    @TempDir
    Path root;

    private static boolean autoDetach(Git git) {
        return git.getRepository().getConfig().getBoolean("gc", null, "autoDetach", true);
    }

    @Test
    void jgitsAutomaticGcRunsInTheForeground() throws Exception {
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            assertFalse(autoDetach(git));
        }
    }

    /**
     * Including under the tests that keep JGit away from the developer's own config by
     * replacing the user and system layers - one of them was where the failure first showed.
     */
    @Test
    void evenUnderATestThatReplacesTheUserAndSystemConfig() throws Exception {
        SystemReader original = SystemReader.getInstance();
        Path empty = Files.writeString(root.resolve("empty.gitconfig"), "");
        SystemReader.setInstance(new SystemReader.Delegate(original) {
            @Override
            public FileBasedConfig openUserConfig(Config parent, FS fs) {
                return new FileBasedConfig(parent, empty.toFile(), fs);
            }

            @Override
            public FileBasedConfig openSystemConfig(Config parent, FS fs) {
                return new FileBasedConfig(parent, empty.toFile(), fs);
            }
        });
        try (Git git = Git.init().setDirectory(root.resolve("repo").toFile()).call()) {
            assertFalse(autoDetach(git));
        } finally {
            SystemReader.setInstance(original);
        }
    }

    /**
     * And it lives in memory only. A config that inherits it writes only its own entries
     * when saved - which is what keeps it out of the developer's {@code ~/.gitconfig} when
     * the application saves the user's config.
     */
    @Test
    void theSettingIsWrittenNowhere() throws Exception {
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.save();
        }
        String saved = Files.readString(root.resolve(".git/config")).toLowerCase(Locale.ROOT);
        assertFalse(saved.contains("autodetach"), saved);
    }
}
