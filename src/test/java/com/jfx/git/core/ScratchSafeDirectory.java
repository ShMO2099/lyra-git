package com.jfx.git.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * {@link SafeDirectory#trust} pointed at a scratch global config, for tests outside this
 * package that drive "Trust this folder".
 *
 * <p>The environment overload stays package-private in the product: nothing there should
 * be able to aim the write anywhere but the user's global config. Tests need the
 * opposite guarantee - that the developer's own config is never touched - and get it
 * here, through the same {@code GIT_CONFIG_GLOBAL} {@code SafeDirectoryTest} uses.
 */
public final class ScratchSafeDirectory {

    private ScratchSafeDirectory() {
    }

    /**
     * A trust action that writes {@code safe.directory} to {@code globalConfig} instead of
     * the user's.
     *
     * <p>Only with git installed. Without it {@code trust} falls back to JGit's user
     * config, which ignores the environment and would write the developer's real one - so
     * then this refuses instead of writing anywhere. Callers assume {@link #gitInstalled}.
     */
    public static Function<String, GitResult> trustingInto(Path globalConfig) {
        Map<String, String> environment = Map.of("GIT_CONFIG_GLOBAL", globalConfig.toString());
        return path -> gitInstalled()
                ? SafeDirectory.trust(path, environment)
                : GitResult.failed("git is not installed; refusing to write the real global config");
    }

    /** Whether git is installed - {@link #trustingInto} writes nothing without it. */
    public static boolean gitInstalled() {
        return NativeGitRunner.isGitInstalled();
    }
}
