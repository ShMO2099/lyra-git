package com.jfx.git.ui.views;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A gone-only branch can contain the only copy of work, so cleanup must ask before selecting it. */
class IncompleteBranchCleanupSelectionTest {

    @Test
    void goneOnlyBranchStartsUnselectedWhileMergedCandidatesStartSelected() {
        var goneOnly = new BranchCleanupView.Found("gone-work", "abc123", false, true,
                "origin/gone-work", "origin", "gone-work", "keep this work", Instant.EPOCH);
        var mergedAndGone = new BranchCleanupView.Found("merged-gone", "def456", true, true,
                "origin/merged-gone", "origin", "merged-gone", "already integrated", Instant.EPOCH);

        var goneRow = new BranchCleanupView.Row(goneOnly);
        var mergedRow = new BranchCleanupView.Row(mergedAndGone);

        assertFalse(goneRow.selected.get(), "unmerged work must require an explicit selection");
        assertEquals("Upstream gone, not merged into main", goneOnly.reason("main"));
        assertFalse(goneOnly.onRemote(), "a gone upstream cannot be deleted remotely again");
        assertTrue(mergedRow.selected.get(), "a branch already merged into the base is safe to preselect");
    }
}
