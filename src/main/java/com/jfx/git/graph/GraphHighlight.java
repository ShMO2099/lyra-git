package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which commits a hovered branch contains, so the rest can be faded.
 *
 * <h2>What "related" means</h2>
 * Ancestry from a branch tip: the commits that branch holds. A commit on a parallel line
 * the branch never reached is what the fade is for — it is the visual answer to "what is
 * on this branch?".
 *
 * <h2>Only the pointer asks</h2>
 * Selecting a commit used to fade everything outside its ancestry as well. That was
 * removed: something is almost always selected — the working-tree row is, on open — so it
 * made the fade the graph's resting state rather than an answer to anything, and it is
 * not what commercial clients do, where a selected commit leaves every other subject at full
 * brightness. A hover is a question and gets an answer; nothing else touches the graph.
 *
 * <h2>Bounded by the page, deliberately</h2>
 * The walk runs over the rows the graph has actually loaded rather than the repository.
 * Reading the whole history to decide a shade of grey would stall the UI on a large
 * repository, and the commits not on the page are not on screen to be faded. The cost is
 * that a relative below the loading horizon is not known to be one — which is invisible,
 * because it is not drawn either.
 */
public final class GraphHighlight {

    /** Hashes related to the selection. Empty when nothing is highlighted. */
    private Set<String> related = Set.of();

    /** True while a selection is driving the fade. */
    private boolean active;

    /**
     * Hashes on the branch under the pointer, and whether one is there at all.
     *
     * <h2>Why hovering is a separate layer from selecting</h2>
     * A hover is a question ("what is on this branch?"), a selection is a place you have
     * gone. Keeping them apart lets the pointer answer its question over the top of the
     * selection and then hand the view back untouched when it leaves, which is what makes
     * it safe to sweep across a rail of refs: no click, no history, nothing to undo.
     * Folding them together would mean either a hover destroying the selection's fade or
     * the selection's fade suppressing the hover.
     */
    private Set<String> hoveredBranch = Set.of();
    private boolean hovering;

    /**
     * The loaded page, so a row can hover a branch without being handed the list.
     *
     * <p>A cell knows its own commit and nothing else; the walk needs every row. Holding
     * the page here keeps that plumbing out of the cell's constructor, which already
     * carries six collaborators.
     */
    private List<GitGraphRow> page = List.of();

    /**
     * The page's commits by hash, and each branch name's tip, built once per page.
     *
     * <h2>Why once</h2>
     * Both were rebuilt on every hover: a hash map over the whole page before the walk
     * could start, and a scan of every row's refs to find a sidebar name's tip. On a
     * 5,000-row page that was 0.8 ms and a megabyte of garbage each time the pointer
     * crossed a chip - and a pointer sweeping a rail of refs crosses one every few
     * frames. The page only changes in {@link #attach}, so that is where they are built.
     */
    private Map<String, CommitInfo> byHash = Map.of();
    private Map<String, String> tipByName = Map.of();

    /**
     * The last branch walked and what it held. A pointer goes back and forth over the
     * same chip far more often than it meets a new one, and the walk's answer for a tip
     * does not change while the page does not.
     */
    private String lastTip;
    private Set<String> lastBranch = Set.of();

    /** Points the hover walks at the currently loaded rows. */
    public void attach(List<GitGraphRow> rows) {
        page = rows == null ? List.of() : List.copyOf(rows);
        Map<String, CommitInfo> hashes = new HashMap<>(page.size() * 2);
        Map<String, String> tips = new HashMap<>();
        for (GitGraphRow row : page) {
            CommitInfo commit = row.getCommit();
            if (commit == null) {
                continue;
            }
            hashes.put(commit.getHash(), commit);
            for (var ref : commit.getRefs()) {
                // The first row carrying a name is its tip, as the scan this replaces
                // found it: the page is in graph order, newest first.
                if (!ref.isTag()) {
                    tips.putIfAbsent(ref.shortName(), commit.getHash());
                }
            }
        }
        byHash = hashes;
        tipByName = tips;
        lastTip = null;
        lastBranch = Set.of();
    }

    /**
     * Bumped whenever the related set changes, so rows can update themselves.
     *
     * <h2>Why rows listen rather than being refreshed</h2>
     * The obvious way to repaint the fade is {@code ListView.refresh()}. It is the wrong
     * tool twice over. Called inside the selection change it rebuilds cells while the
     * model's indices are half updated; deferred out of it with {@code runLater} the fade
     * lands a pulse after the selection, so a row that should have dimmed stays bright for
     * one frame — which is exactly what a user sees as a flash.
     *
     * <p>A row that watches this property instead updates in the same pulse as the
     * selection and never touches the virtual flow, so there is no frame in which the
     * fade and the selection disagree.
     */
    private final javafx.beans.property.IntegerProperty revision =
            new javafx.beans.property.SimpleIntegerProperty();

    /** Changes whenever the fade changes; rows listen to this rather than being refreshed. */
    public javafx.beans.value.ObservableValue<Number> revisionProperty() {
        return revision;
    }

    /**
     * Whether {@code hash} should be drawn faded.
     *
     * <p>False for everything while inactive, so no selection means no fading rather than
     * everything fading.
     */
    public boolean isFaded(String hash) {
        if (hash == null) {
            return false;
        }
        // The pointer wins while it is on a ref: a hover is a transient question asked
        // over whatever the selection had already dimmed, and answering it with the union
        // of the two would fade nothing on a branch that contains the selection.
        if (hovering) {
            return !hoveredBranch.contains(hash);
        }
        return active && !related.contains(hash);
    }

    public boolean isActive() {
        return hovering || active;
    }

    /**
     * Fades everything that is not an ancestor of {@code tipHash} - the branch-hover
     * preview.
     *
     * <h2>Ancestors only, unlike {@link #select}</h2>
     * A branch <em>is</em> its tip and what the tip descends from. Commits that descend
     * from the tip are by definition on some other branch that built on top of this one,
     * so including them - as {@link #select} rightly does for a commit - would light up
     * the neighbours the hover is meant to dim.
     */
    public void hoverBranch(String tipHash) {
        if (tipHash == null || page.isEmpty() || !byHash.containsKey(tipHash)) {
            clearHover();
            return;
        }
        if (!tipHash.equals(lastTip)) {
            Set<String> found = new HashSet<>();
            found.add(tipHash);
            Deque<String> pending = new ArrayDeque<>();
            pending.add(tipHash);
            while (!pending.isEmpty()) {
                CommitInfo commit = byHash.get(pending.poll());
                if (commit == null) {
                    continue;
                }
                for (String parent : commit.getParentHashes()) {
                    if (byHash.containsKey(parent) && found.add(parent)) {
                        pending.add(parent);
                    }
                }
            }
            // Wrapped rather than copied: nothing else holds the set, and Set.copyOf of
            // five thousand hashes was a second walk's worth of hashing per hover.
            lastBranch = java.util.Collections.unmodifiableSet(found);
            lastTip = tipHash;
        } else if (hovering && hoveredBranch == lastBranch) {
            // Already showing exactly this; a revision bump would re-fade every live row
            // to the same answer.
            return;
        }
        hoveredBranch = lastBranch;
        hovering = true;
        revision.set(revision.get() + 1);
    }

    /**
     * Hovers whichever commit the branch called {@code shortName} points at.
     *
     * <p>For the sidebar, which knows a branch by its name and nothing else. The page
     * already carries every row's refs, so the tip is a lookup rather than another trip
     * to the repository - which matters when the source is a pointer sweeping down a
     * list of forty branches.
     *
     * <p>A name that is not on the page fades nothing: the branch is real but its tip is
     * below the loading horizon, and fading the entire graph is a worse answer than
     * leaving it alone.
     */
    public void hoverBranchNamed(String shortName) {
        if (shortName == null || shortName.isBlank()) {
            clearHover();
            return;
        }
        String tip = tipByName.get(shortName);
        if (tip != null) {
            hoverBranch(tip);
            return;
        }
        clearHover();
    }

    /**
     * Ends the hover preview, handing the view back to the selection's fade.
     *
     * <p>Silent when nothing was hovered: pointers leave nodes constantly, and bumping
     * the revision on each of those would relayout every visible row for no change.
     */
    public void clearHover() {
        if (!hovering) {
            return;
        }
        hoveredBranch = Set.of();
        hovering = false;
        revision.set(revision.get() + 1);
    }

    /** The related set, for tests and for anything that wants to count it. */
    public Set<String> related() {
        return Set.copyOf(related);
    }

    /**
     * Highlights exactly {@code matchedHashes} — the search-result fade (§2.5): matches
     * stay bright, everything else dims, but nothing leaves the graph.
     *
     * <p>Separate from {@link #select} so a caller can give search precedence over the
     * selection while a query is live without either erasing the other's state.
     */
    public void search(Set<String> matchedHashes) {
        related = matchedHashes == null ? Set.of() : Set.copyOf(matchedHashes);
        active = true;
        revision.set(revision.get() + 1);
    }

    /**
     * Stops fading anything.
     *
     * <p>Silent when nothing was fading, like {@link #clearHover()}: every selection of
     * the working-tree row or a stash calls this, and a revision bump re-fades every live
     * row to the answer it already shows.
     */
    public void clear() {
        if (!active && !hovering) {
            related = Set.of();
            hoveredBranch = Set.of();
            return;
        }
        related = Set.of();
        active = false;
        hoveredBranch = Set.of();
        hovering = false;
        revision.set(revision.get() + 1);
    }
}
