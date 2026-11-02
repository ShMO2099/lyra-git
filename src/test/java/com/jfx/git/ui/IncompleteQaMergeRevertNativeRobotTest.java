package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.NativeGitBackend;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IncompleteQaMergeRevertRobotTest}'s journeys on the git CLI: the engine that does the
 * writing for the default Hybrid engine, and which reverts a merge with git's own
 * {@code revert -m} rather than the three-way merge the JGit engine runs in its place.
 */
class IncompleteQaMergeRevertNativeRobotTest extends IncompleteQaMergeRevertRobotTest {

    @Override
    protected GitBackend openEngine(Path workTree) throws Exception {
        NativeGitBackend cli = new NativeGitBackend();
        assertTrue(cli.openRepository(workTree.toFile()));
        return cli;
    }
}
