package com.jfx.git.graph;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RefDropMenu;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which of the graph's ref chips can be dragged, and as what.
 *
 * <p>A chip is dragged as the branch it stands for. A tag is a point rather than a line
 * of work, and the "HEAD" chip of a detached checkout names no branch at all - dragging
 * either onto a branch would offer to merge something that is not one.
 */
class ChipDragTest {

    /** {@link GitGraphCell} is a control; loading the class needs the toolkit. */
    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    private static RefRail.Chip chip(String name, RefRail.Kind kind, boolean head, String tooltip) {
        return new RefRail.Chip(name, kind, head, false, tooltip);
    }

    @Test
    void aLocalBranchChipIsDraggedAsALocalBranch() {
        assertEquals(RefDropMenu.Ref.local("feature/x"),
                GitGraphCell.draggableRef(chip("feature/x", RefRail.Kind.LOCAL, false, "feature/x")));
        assertEquals(RefDropMenu.Ref.local("main"),
                GitGraphCell.draggableRef(chip("main", RefRail.Kind.LOCAL, true, "main (HEAD)")),
                "the branch HEAD is on is still a branch");
    }

    @Test
    void aRemoteBranchChipIsDraggedAsARemoteBranch() {
        assertEquals(RefDropMenu.Ref.remote("origin/main"),
                GitGraphCell.draggableRef(chip("origin/main", RefRail.Kind.REMOTE, false, "origin/main")));
    }

    @Test
    void aTagAndADetachedHeadAreNotDraggable() {
        assertNull(GitGraphCell.draggableRef(chip("v1.0", RefRail.Kind.TAG, false, "tag: v1.0")));
        assertNull(GitGraphCell.draggableRef(chip("HEAD", RefRail.Kind.LOCAL, true, "HEAD (detached)")));
        assertNull(GitGraphCell.draggableRef(null));
    }

    /** What {@link RefRail} writes for a detached checkout is what the check above keys on. */
    @Test
    void theDetachedChipIsRecognisedAsRefRailBuildsIt() {
        var chips = RefRail.chipsFor(
                java.util.List.of(com.jfx.git.model.RefInfo.detachedHead("abc")), null);
        assertEquals(1, chips.size());
        assertNull(GitGraphCell.draggableRef(chips.get(0)),
                "a detached HEAD chip must not be offered as a branch: " + chips.get(0));
    }
}
