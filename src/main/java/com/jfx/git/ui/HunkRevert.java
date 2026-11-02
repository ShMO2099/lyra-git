package com.jfx.git.ui;

import com.jfx.git.ui.dialogs.Dialogs;

import java.util.List;

/**
 * Runs "Revert hunk": a commit's hunk undone in the working tree, left as an uncommitted
 * edit. One flow for every place a commit's diff is shown - the commit details panel and
 * File history - so the two cannot drift apart in what they ask or what they guard.
 *
 * <p>Asked first, then run behind a working-tree snapshot with Undo, as a discard is: the
 * file it lands in may hold uncommitted work of the user's own around the hunk.
 */
final class HunkRevert {

    private HunkRevert() {
    }

    /**
     * @param target        the working-tree file to revert into: today's name, which for a
     *                      hunk from before a rename is not the name the commit used
     * @param subtitle      where the hunk comes from, for the question
     * @param openSnapshots opens the snapshot list; {@code null} leaves that out
     * @param refresh       what the revert, or its undo, redraws once done
     */
    static void confirmAndRun(OperationRunner operations, HunkAction action, String target, String subtitle,
                              Runnable openSnapshots, OperationRunner.Refresh refresh) {
        // Enter goes ahead: what is taken out is committed, so the commit still has it.
        boolean go = Dialogs.destructive("Revert hunk")
                .icon(Icons.undo())
                .title("Revert this hunk in `" + target + "`?")
                .subtitle(subtitle)
                .message("Its change is taken back out of your working copy, as an uncommitted edit"
                        + " you can review. The commit itself is not modified. A snapshot is taken"
                        + " first, and the notification that follows offers Undo.")
                .action("Revert hunk")
                .ask();
        if (go) {
            SnapshotGuard.discard(operations, "Reverting hunk in " + target,
                    "Before reverting a hunk in " + target, List.of(target),
                    () -> operations.repository().revertHunk(target, action.edit()), openSnapshots, refresh);
        }
    }
}
