package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.RefNames;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a commit's refs into the chips the rail shows.
 *
 * <p>Three things happen here, and all three exist because the raw ref list reads badly.
 *
 * <p><strong>A branch and its remote counterpart become one chip.</strong> {@code main}
 * and {@code origin/main} are one branch in two places, and drawing them as two chips
 * spends twice the width to say so. They collapse into a single chip carrying a "tracked"
 * mark; when the two have diverged the remote keeps a chip of its own, because then they
 * genuinely are two different positions.
 *
 * <p><strong>{@code origin/HEAD} is dropped.</strong> It is a symbolic ref pointing at
 * whatever the remote calls its default branch — never news, and always sitting next to
 * the branch it duplicates.
 *
 * <p><strong>The rail has a fixed width</strong>, so what does not fit becomes a
 * {@code +n} chip rather than pushing the commit message sideways. That was the original
 * complaint: a long branch name should cost the branch column, not the message.
 */
public final class RefRail {

    private RefRail() {}

    /** What a chip stands for, which decides how it is drawn. */
    public enum Kind { LOCAL, REMOTE, TAG }

    /**
     * One chip on the rail.
     *
     * @param name    what the chip reads
     * @param kind    local branch, remote-tracking branch, or tag
     * @param head    this is the branch HEAD is on
     * @param tracked a remote counterpart exists at the same commit, folded into this chip
     * @param tooltip the full text, for when the chip had to be ellipsised
     */
    public record Chip(String name, Kind kind, boolean head, boolean tracked, String tooltip) {}

    /**
     * The chips for one commit, in display order, with its upstreams where known.
     *
     * <p>Preferred over the list overload: a graph row carries which local branch tracks
     * which remote (see {@link CommitInfo#getUpstreams()}), and only that pair is folded.
     */
    public static List<Chip> chipsOf(CommitInfo commit, String currentBranch) {
        if (commit == null) return List.of();
        return chipsFor(commit.getRefs(), currentBranch, commit.getUpstreams());
    }

    /**
     * The chips for one commit, in display order, without tracking information.
     *
     * <p>Folds a remote into a local branch of the same name, which is right for the
     * common single-remote case and is the best that can be done without knowing what
     * each branch tracks. Callers holding a graph row should use
     * {@link #chipsOf(CommitInfo, String)}.
     *
     * @param refs          the commit's refs, in any order
     * @param currentBranch the branch HEAD is on, or {@code null} when detached
     */
    public static List<Chip> chipsFor(List<RefInfo> refs, String currentBranch) {
        return chipsFor(refs, currentBranch, null);
    }

    /**
     * The chips for one commit, in display order.
     *
     * @param upstreams local branch short name to the short name of the remote-tracking
     *                  branch it tracks; {@code null} when unknown, which falls back to
     *                  folding by name alone
     */
    public static List<Chip> chipsFor(List<RefInfo> refs, String currentBranch,
                                      Map<String, String> upstreams) {
        if (refs == null || refs.isEmpty()) return List.of();

        List<RefInfo> ordered = new ArrayList<>(refs);
        ordered.sort(RefInfo.DISPLAY_ORDER);

        // Local branch names present on this commit, so a remote ref can tell whether its
        // counterpart is here too rather than merely existing somewhere in the repository.
        Set<String> localNames = new HashSet<>();
        Set<String> remoteNames = new HashSet<>();
        for (RefInfo ref : ordered) {
            if (ref.isLocalBranch()) localNames.add(ref.shortName());
            if (ref.isRemoteBranch()) remoteNames.add(ref.shortName());
        }
        // With tracking known, a remote folds only into the branch that tracks it. Folding
        // by name alone drew "main ✓" for an upstream/main that main does not track, and
        // the user then read their fork's branch as in sync with origin.
        Set<String> foldedRemotes = new HashSet<>();
        if (upstreams != null) {
            for (String local : localNames) {
                String upstream = upstreams.get(local);
                if (upstream != null && remoteNames.contains(upstream)) foldedRemotes.add(upstream);
            }
        }

        List<Chip> chips = new ArrayList<>(ordered.size());
        for (RefInfo ref : ordered) {
            if (ref.isDetachedHead()) {
                // Commits made now belong to no branch; that is worth a chip of its own.
                chips.add(new Chip("HEAD", Kind.LOCAL, true, false, "HEAD (detached)"));
                continue;
            }
            if (ref.isTag()) {
                chips.add(new Chip(ref.shortName(), Kind.TAG, false, false, "tag: " + ref.shortName()));
                continue;
            }

            if (ref.isRemoteBranch()) {
                String name = ref.shortName();
                // The engines leave every remote's HEAD out of the snapshot; refs handed in
                // from elsewhere meet the rule as far as it goes knowing no remotes - origin/HEAD
                // is caught, a team/core remote's is not; see GraphPageBuilder.isGraphRef.
                if (RefNames.isSymbolicHead(name, List.of())) continue;
                // Folded into the local chip when the branch tracking it is on this commit.
                boolean folded = upstreams != null
                        ? foldedRemotes.contains(name)
                        : localNames.contains(strippedRemote(name));
                if (folded) continue;
                chips.add(new Chip(name, Kind.REMOTE, false, false, name));
                continue;
            }

            String name = ref.shortName();
            boolean head = name.equals(currentBranch);
            boolean tracked = upstreams != null
                    ? foldedRemotes.contains(upstreams.get(name))
                    : hasRemoteCounterpart(ordered, name);
            String tooltip = head ? name + " (HEAD)" : name;
            chips.add(new Chip(name, Kind.LOCAL, head, tracked, tooltip));
        }
        // The checked-out branch leads, whatever its name - the order commercial clients use. The rail
        // shows only the first chip or two before "+N", and alphabetical order put
        // "editor-parity" in front of the master the user had just checked out, leaving
        // the branch they were on hidden in the count.
        for (int i = 1; i < chips.size(); i++) {
            if (chips.get(i).head()) {
                chips.add(0, chips.remove(i));
                break;
            }
        }
        return chips;
    }

    /**
     * The branch part of {@code origin/feature/x}, {@code feature/x}, for the name-only
     * fold - which knows no configured remotes, so {@link RefNames#branchOf} takes the
     * first segment as the remote. A branch may itself contain slashes:
     * {@code backup/before-reword} pushed to {@code origin} is
     * {@code origin/backup/before-reword}, not {@code backup}.
     */
    static String strippedRemote(String remoteShortName) {
        return RefNames.branchOf(remoteShortName, List.of());
    }

    private static boolean hasRemoteCounterpart(List<RefInfo> refs, String localName) {
        for (RefInfo ref : refs) {
            if (ref.isRemoteBranch() && strippedRemote(ref.shortName()).equals(localName)) {
                return true;
            }
        }
        return false;
    }
}
