package com.jfx.git.graph;

import com.jfx.git.core.GitBackend;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.RefNames;
import com.jfx.git.model.RefSnapshot;
import com.jfx.git.model.StashEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Everything about a graph page that does not depend on which engine walked it.
 *
 * <h2>Why this exists</h2>
 * The JGit and CLI engines each used to decide for themselves which refs start the walk,
 * which refs decorate a row, where the uncommitted row goes and how the page is cut - and
 * they drifted. The CLI walked {@code --all}, so stash internals, notes and the app's own
 * {@code refs/lyra/*} appeared as history there and nowhere else (G1); it named HEAD
 * even when HEAD did not resolve, so an orphan branch drew an empty graph (G8). Each
 * engine now only walks and parses; the decisions are made here, once.
 *
 * <p>Pure Java with no engine types, so it can be tested without a repository.
 */
public final class GraphPageBuilder {

    private GraphPageBuilder() {}

    /**
     * Where the walk starts: HEAD when it resolves, then the tip of every branch,
     * remote-tracking branch and tag that Hide/Solo leaves standing.
     *
     * <p>Exactly {@code --branches --remotes --tags} plus HEAD - never {@code --all},
     * which also walks {@code refs/stash}, {@code refs/notes/*}, {@code refs/lyra/*} and
     * anything else a tool left under {@code refs/}. Returned as hashes, deduplicated, so
     * the CLI engine can feed them to {@code git log --stdin} and the command line does
     * not grow with the number of refs (N11).
     *
     * @param refs     every local branch, remote-tracking branch and tag
     * @param headHash HEAD's commit, or {@code null} when unborn
     */
    public static List<String> startPoints(Collection<RefInfo> refs, GraphVisibility visibility,
                                           String headHash) {
        visibility = (visibility == null ? GraphVisibility.ALL : visibility).over(refs);
        Set<String> starts = new LinkedHashSet<>();
        // HEAD unconditionally: the graph you are looking at is drawn from the commit you
        // are standing on, whatever Hide/Solo says about other branches.
        if (headHash != null) starts.add(headHash);
        for (RefInfo ref : refs) {
            if (!isGraphRef(ref) || ref.targetHash() == null) continue;
            if (visibility.excludes(ref)) continue;
            starts.add(ref.targetHash());
        }
        return new ArrayList<>(starts);
    }

    /**
     * {@link #startPoints(Collection, GraphVisibility, String)}, plus the commit each stash
     * was made on - unless a ref the user has hidden reaches it.
     *
     * <p>A stash's base can be a commit nothing else reaches any more: the branch it was
     * made on rebased, amended or deleted since. Walked only from branches and tags, that
     * commit never made the page - and a stash row sits beside its base or not at all, so
     * the stash vanished from the graph and clicking it in the sidebar had nothing to
     * select. Commercial clients draw that history; so does this.
     *
     * <p>Not when a hidden branch or tag still reaches the base: a stash made on
     * {@code feature/big} would otherwise bring all of {@code feature/big} back,
     * undecorated, the moment the user hid it, and the eye would visibly do nothing. Nor
     * under Solo, which shows one ref's history and nothing else. Whether a hidden ref
     * reaches a commit is the engine's to answer; it is asked only about the hidden refs
     * that exist - never every branch and tag, which in a repository of thousands of tags
     * cost a walk of all of them per stash on every page - and only for a base no visible
     * tip already is.
     *
     * @param stashes     the stashes the graph shows - hidden ones already removed
     * @param reachedFrom whether a commit, by its hash, is in the history of any of the
     *                    given refs, by full name; not called when no existing ref is hidden
     */
    public static List<String> startPoints(Collection<RefInfo> refs, GraphVisibility visibility,
                                           String headHash, List<StashEntry> stashes,
                                           BiPredicate<String, Collection<String>> reachedFrom) {
        visibility = (visibility == null ? GraphVisibility.ALL : visibility).over(refs);
        List<String> starts = startPoints(refs, visibility, headHash);
        if (stashes == null || stashes.isEmpty() || visibility.soloRef() != null) {
            return starts;
        }
        // Names the user hid but that no longer exist - a branch deleted since - hide
        // nothing, so they are not asked about.
        List<String> hidden = new ArrayList<>();
        for (RefInfo ref : refs) {
            if (isGraphRef(ref) && visibility.hiddenRefs().contains(ref.fullName())) hidden.add(ref.fullName());
        }
        Set<String> all = new LinkedHashSet<>(starts);
        for (StashEntry stash : stashes) {
            String base = stash.parentHash();
            if (base == null || all.contains(base)) continue;
            if (!hidden.isEmpty() && reachedFrom != null && reachedFrom.test(base, hidden)) continue;
            all.add(base);
        }
        return new ArrayList<>(all);
    }

    /**
     * Which refs decorate which commit, keyed by commit hash, each list in display order.
     *
     * <p>A ref Hide/Solo excludes does not decorate either: a hidden branch whose tip is
     * still reachable from elsewhere used to keep its chip, so hiding it visibly did
     * nothing. The branch HEAD is on always keeps its chip - its commits are always
     * walked - and a detached HEAD gets a marker of its own ({@link RefInfo#detachedHead}).
     *
     * @param currentBranchRef the full name of the branch HEAD is on
     *                         ({@code refs/heads/main}), or {@code null} when detached
     */
    public static Map<String, List<RefInfo>> decorations(Collection<RefInfo> refs,
                                                         GraphVisibility visibility,
                                                         String headHash,
                                                         String currentBranchRef) {
        visibility = (visibility == null ? GraphVisibility.ALL : visibility).over(refs);
        Map<String, List<RefInfo>> byHash = new HashMap<>();
        for (RefInfo ref : refs) {
            if (!isGraphRef(ref) || ref.targetHash() == null) continue;
            boolean current = ref.fullName().equals(currentBranchRef);
            if (!current && visibility.excludes(ref)) continue;
            byHash.computeIfAbsent(ref.targetHash(), k -> new ArrayList<>()).add(ref);
        }
        if (headHash != null && currentBranchRef == null) {
            byHash.computeIfAbsent(headHash, k -> new ArrayList<>()).add(RefInfo.detachedHead(headHash));
        }
        byHash.values().forEach(list -> list.sort(RefInfo.DISPLAY_ORDER));
        return byHash;
    }

    /**
     * Local branch short name to its upstream's short name, for the refs that track a
     * remote-tracking branch; see {@link RefRail#chipsOf(CommitInfo, String)}.
     *
     * @param upstreamOf local full name to the full name of what it tracks
     */
    public static Map<String, String> upstreamShortNames(Map<String, String> upstreamOf) {
        Map<String, String> shortNames = new HashMap<>();
        if (upstreamOf == null) return shortNames;
        upstreamOf.forEach((local, upstream) -> {
            if (local != null && upstream != null && upstream.startsWith("refs/remotes/")) {
                shortNames.put(RefInfo.shortNameOf(local), RefInfo.shortNameOf(upstream));
            }
        });
        return shortNames;
    }

    /**
     * A branch, remote-tracking branch or tag the app did not fabricate for itself, and
     * not a remote's symbolic {@code HEAD}.
     *
     * <p>{@code origin/HEAD} points at another remote branch: as a start it walked that
     * branch's history back in after the user hid it, and as a decoration it doubled that
     * branch's chip. The engines, which know the remotes, leave every remote's out of the
     * snapshot already. Refs handed in from anywhere else meet the same rule here as far
     * as it goes without the remotes' names: the first segment is taken as the remote, so
     * {@code origin/HEAD} is caught but the {@code HEAD} of a remote named {@code team/core}
     * reads as a branch {@code core/HEAD} - as {@code origin/feature/HEAD}, a branch, must.
     */
    private static boolean isGraphRef(RefInfo ref) {
        if (ref == null || ref.fullName() == null) return false;
        if (ref.isLocalBranch() || ref.isTag()) return true;
        return ref.isRemoteBranch() && !GitBackend.isSyntheticRemoteRef(ref.fullName())
                && !RefNames.isSymbolicHead(ref.fullName(), List.of());
    }

    /**
     * What an engine hands over once it has walked.
     *
     * @param walk              commits in walk order (newest committer date first), decorated
     * @param walkComplete      the walk reached the end of history, not a fetch limit
     * @param headHash          HEAD's commit, or {@code null} when unborn
     * @param dirty             whether the uncommitted row is shown
     * @param stashes           the stashes to draw, already filtered for Hide
     * @param reachableFromHead what HEAD reaches, or {@code null} when not computed
     * @param upstreams         local branch to upstream, short names; see
     *                          {@link #upstreamShortNames}
     * @param refs              the ref snapshot the walk was read from; it rides on the
     *                          page, and its remote names decide each remote branch's
     *                          branch part for the lane colours
     */
    public record Input(List<CommitInfo> walk, boolean walkComplete, String headHash,
                        GraphOrder order, int limit, boolean dirty, List<StashEntry> stashes,
                        Set<String> reachableFromHead, Map<String, String> upstreams, RefSnapshot refs) {

        public Input {
            refs = refs == null ? RefSnapshot.EMPTY : refs;
        }

        /** An input with no ref snapshot: a test's walk, laid out without a repository. */
        public Input(List<CommitInfo> walk, boolean walkComplete, String headHash,
                     GraphOrder order, int limit, boolean dirty, List<StashEntry> stashes,
                     Set<String> reachableFromHead, Map<String, String> upstreams) {
            this(walk, walkComplete, headHash, order, limit, dirty, stashes, reachableFromHead, upstreams,
                    RefSnapshot.EMPTY);
        }

        /**
         * What an engine hands over: its walk, and the snapshot that walk started from -
         * HEAD and the upstreams are the snapshot's.
         *
         * @param stashes the stashes to draw; see {@link #shownStashes}
         */
        public static Input of(List<CommitInfo> walk, boolean walkComplete, GraphOrder order, int limit,
                               boolean dirty, RefSnapshot refs, List<StashEntry> stashes,
                               Set<String> reachableFromHead) {
            return new Input(walk, walkComplete, refs.headHash(), order, limit, dirty, stashes,
                    reachableFromHead, upstreamShortNames(refs.upstreams()), refs);
        }
    }

    /**
     * The stashes the graph draws: the snapshot's, less the ones the user hid. The snapshot
     * keeps them all, since the sidebar lists hidden stashes too.
     */
    public static List<StashEntry> shownStashes(RefSnapshot refs, Set<String> hidden) {
        return StashRows.visible(refs.stashes(), hidden);
    }

    /** Orders, cuts and lays out one page. */
    public static GraphPage build(Input in) {
        CommitOrdering.Page page = CommitOrdering.page(in.walk(), in.walkComplete(),
                in.headHash(), in.order(), in.limit());
        List<CommitInfo> commits = page.commits();

        Map<String, String> upstreams = in.upstreams() == null ? null : Map.copyOf(in.upstreams());
        for (CommitInfo ci : commits) {
            // Commits beyond the reach of the HEAD walk keep the default "on the branch";
            // the context menu asks git properly before acting on one.
            if (in.reachableFromHead() != null && !in.reachableFromHead().isEmpty()) {
                ci.setOnCurrentBranch(in.reachableFromHead().contains(ci.getHash()));
            }
            ci.setUpstreams(upstreams);
        }

        // The uncommitted row is laid out as a commit whose parent is HEAD, the way
        // gitfourchette does it: it then takes HEAD's lane and draws an edge down to HEAD
        // wherever HEAD sits, rather than floating in lane 0.
        List<CommitInfo> layout = new ArrayList<>(commits.size() + 1);
        CommitInfo pseudo = null;
        if (in.dirty()) {
            pseudo = GitGraphRow.uncommittedPseudoCommit(in.headHash());
            layout.add(pseudo);
        }
        layout.addAll(commits);
        int laneCount = LaneAssigner.assign(layout, in.refs().remoteNames());

        List<GitGraphRow> rows = new ArrayList<>(commits.size() + 1);
        if (pseudo != null) rows.add(GitGraphRow.uncommitted(pseudo));
        for (CommitInfo ci : commits) {
            rows.add(new GitGraphRow(ci));
        }
        rows.forEach(row -> row.setTotalLanes(laneCount));
        StashRows.interleave(rows, in.stashes(), laneCount);
        return new GraphPage(rows, page.hasMore(), in.refs());
    }
}
