package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.NativeGitBackend;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IncompleteQaRenameHistoryRobotTest}'s journeys on the git CLI, which reads file
 * history with {@code log --follow} and reverts hunks for the default Hybrid engine.
 */
class IncompleteQaRenameHistoryNativeRobotTest extends IncompleteQaRenameHistoryRobotTest {

    @Override
    protected GitBackend openEngine(Path workTree) throws Exception {
        NativeGitBackend cli = new NativeGitBackend();
        assertTrue(cli.openRepository(workTree.toFile()));
        return cli;
    }
}
