package com.jfx.git.ui;

import com.jfx.git.core.GitTaskRunner;

/**
 * Stops a reset that would be refused anyway, and says why - before anything asks the
 * user to confirm it.
 *
 * <p>Restoring is a hard reset, and the working tree is not recoverable from the reflog
 * the way the history is, so uncommitted changes block it. That policy belongs to
 * {@link com.jfx.git.core.RepositoryCommands#restoreTo}; this only reports it early, in the same words wherever a
 * reset is offered - the toolbar's Undo and Branch history's Restore - so the user is told
 * what to do instead of being asked a question whose "OK" does nothing.
 */
public final class WorkingTreeGuard {

    private WorkingTreeGuard() {
    }

    /**
     * Runs {@code proceed} on the FX thread if the working tree is clean; otherwise reports
     * the refusal on the tab's status bar and does nothing else.
     *
     * @param action what is being refused, as the start of a sentence: "Undo: commit",
     *               "Restoring main to 1a2b3c4"
     */
    public static void whenClean(OperationRunner operations, String action, Runnable proceed) {
        StatusBar statusBar = operations.statusBar();
        GitTaskRunner.query(operations.repository(),
                operations.repository()::hasUncommittedChanges,
                dirty -> {
                    if (dirty) {
                        statusBar.error(action + " resets the working tree, so it is refused "
                                + "while there are uncommitted changes. Commit or stash them "
                                + "first.");
                        return;
                    }
                    proceed.run();
                },
                statusBar::error);
    }
}
