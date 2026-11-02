package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.NativeGitBackend;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IncompleteQaRenamedFileRestoreRobotTest}'s journeys on the git CLI, which writes
 * for the default Hybrid engine and finds the rename with its own {@code diff-tree -M}.
 */
class IncompleteQaRenamedFileRestoreNativeRobotTest extends IncompleteQaRenamedFileRestoreRobotTest {

    @Override
    protected GitBackend openEngine(Path workTree) throws Exception {
        NativeGitBackend cli = new NativeGitBackend();
        assertTrue(cli.openRepository(workTree.toFile()));
        return cli;
    }
}
