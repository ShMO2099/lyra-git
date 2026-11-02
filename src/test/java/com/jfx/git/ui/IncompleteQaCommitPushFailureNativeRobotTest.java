package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.NativeGitBackend;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IncompleteQaCommitPushFailureRobotTest}'s journeys on the git CLI: the engine that
 * does the writing for the default Hybrid engine, whose refused push comes back as git's
 * several lines of stderr rather than JGit's one.
 */
class IncompleteQaCommitPushFailureNativeRobotTest extends IncompleteQaCommitPushFailureRobotTest {

    @Override
    protected GitBackend openEngine(Path workTree) throws Exception {
        NativeGitBackend cli = new NativeGitBackend();
        assertTrue(cli.openRepository(workTree.toFile()));
        return cli;
    }
}
