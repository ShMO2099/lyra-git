package com.jfx.git.graph;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.model.RefInfo;

import java.util.Set;

/**
 * Which refs the commit graph's walk is allowed to start from.
 *
 * <p>Two independent controls, matching the commercial clients' Hide/Solo pair: a set of refs the
 * user has hidden (excluded, everything else still shown), and at most one ref the user
 * has "solo"ed (only it — and whatever HEAD reaches — is shown, overriding any hides).
 * Solo is exclusive with itself by construction: there is one {@code soloRef} field, not
 * a set, so choosing a second solo replaces the first rather than compounding it, which
 * is what the commercial clients' toggle does.
 *
 * <p>Refs are identified by {@link RefInfo#fullName()} rather than {@link
 * RefInfo#shortName()}: a local {@code main} and a remote {@code origin/main} do not
 * collide, and neither does a branch and a tag that happen to share a name.
 *
 * <p>Passed as an explicit parameter to {@code GitBackend.getCommitGraphPage} rather than
 * read from {@link AppPreferences} inside the walk, for the same reason {@link
 * GraphOrder} is: a caller that wants a predictable page - a test, or a re-render after
 * the user changes the setting mid-session - has to be able to say what it means,
 * instead of getting whichever hidden-set another part of the app last touched.
 */
public record GraphVisibility(Set<String> hiddenRefs, String soloRef) {

    public static final GraphVisibility ALL = new GraphVisibility(Set.of(), null);

    public GraphVisibility {
        hiddenRefs = hiddenRefs == null ? Set.of() : Set.copyOf(hiddenRefs);
    }

    /** Whether HEAD and every branch/tag tip is a walk start, unfiltered. */
    public boolean isUnrestricted() {
        return soloRef == null && hiddenRefs.isEmpty();
    }

    /**
     * Whether {@code ref} should NOT be a walk start.
     *
     * <p>Solo wins outright: with a solo ref set, everything else is excluded regardless
     * of the hidden set (the two are never meant to be reasoned about together — commercial
     * clients treat choosing Solo on a branch as clearing Hide's say in the matter).
     */
    public boolean excludes(RefInfo ref) {
        return ref != null && excludesName(ref.fullName());
    }

    /** {@link #excludes(RefInfo)}, by the ref's full name - for a caller holding only that. */
    public boolean excludesName(String fullName) {
        if (fullName == null) return false;
        if (soloRef != null) return !soloRef.equals(fullName);
        return hiddenRefs.contains(fullName);
    }

    /**
     * This visibility over the refs there are now: a solo on a ref that is not among them -
     * the soloed branch deleted since - solos nothing, as a hidden name no ref has any more
     * hides nothing. Otherwise the graph showed HEAD's history alone, with no row left in
     * the sidebar to clear the solo from. What is stored is left as it is, so Undo of the
     * delete brings the solo back with the branch.
     */
    public GraphVisibility over(java.util.Collection<RefInfo> refs) {
        if (soloRef == null || refs == null) return this;
        for (RefInfo ref : refs) {
            if (soloRef.equals(ref.fullName())) return this;
        }
        return new GraphVisibility(hiddenRefs, null);
    }

    /** The stored hidden-set and solo choice, exactly as {@link AppPreferences} holds them. */
    public static GraphVisibility current() {
        return new GraphVisibility(AppPreferences.graphHiddenBranches(), AppPreferences.graphSoloBranch());
    }
}
