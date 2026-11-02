package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.NativeGitBackend;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IncompleteQaBranchHistoryRobotTest}'s journeys on the git CLI, which resets and
 * reads the reflog for the default Hybrid engine.
 */
class IncompleteQaBranchHistoryNativeRobotTest extends IncompleteQaBranchHistoryRobotTest {

    @Override
    protected GitBackend openEngine(Path workTree) throws Exception {
        NativeGitBackend cli = new NativeGitBackend();
        assertTrue(cli.openRepository(workTree.toFile()));
        return cli;
    }
}
