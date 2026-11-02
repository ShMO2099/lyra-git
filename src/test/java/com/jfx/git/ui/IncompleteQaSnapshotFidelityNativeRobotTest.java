package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.NativeGitBackend;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IncompleteQaSnapshotFidelityRobotTest}'s journeys on the git CLI: the engine that
 * takes, restores and deletes snapshots for the default Hybrid engine, through a temporary
 * index, {@code git restore --worktree --overlay} and {@code update-ref -d} rather than
 * JGit's own tree walks.
 */
class IncompleteQaSnapshotFidelityNativeRobotTest extends IncompleteQaSnapshotFidelityRobotTest {

    @Override
    protected GitBackend openEngine(Path workTree) throws Exception {
        NativeGitBackend cli = new NativeGitBackend();
        assertTrue(cli.openRepository(workTree.toFile()));
        return cli;
    }
}
