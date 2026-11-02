package com.jfx.git.core;

import com.jfx.git.graph.GraphPage;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.CommitPosition;
import com.jfx.git.model.RefInfo;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The commit graph's pages, commit details and positions, history search, file history,
 * blame and the reflog, for {@link GitService}.
 */
final class JGitHistory extends JGitPart {

    JGitHistory(JGitRepo ctx) {
        super(ctx);
    }


    public String mergeBase(String commitA, String commitB) {
        if (repository() == null) return null;
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId a = repository().resolve(commitA);
            ObjectId b = repository().resolve(commitB);
            if (a == null || b == null) return null;
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a));
            walk.markStart(walk.parseCommit(b));
            RevCommit base = walk.next();
            return base == null ? null : base.getName();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "No merge-base for " + commitA + " and " + commitB, e);
            return null;
        }
    }

    /**
     * An in-core recursive merge: the same merger a real merge uses, given no working
     * tree and no index, so all it produces is a result tree and the paths it could not
     * merge. Unrelated histories are refused rather than merged against an empty base,
     * as {@code git merge-tree} refuses them.
     */
    public com.jfx.git.model.ConflictPrediction predictMergeConflicts(String ours, String theirs) {
        if (repository() == null || ours == null || theirs == null) {
            return com.jfx.git.model.ConflictPrediction.unsupported();
        }
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId oursId = repository().resolve(ours + "^{commit}");
            ObjectId theirsId = repository().resolve(theirs + "^{commit}");
            if (oursId == null || theirsId == null) {
                return com.jfx.git.model.ConflictPrediction.unsupported();
            }
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(oursId));
            walk.markStart(walk.parseCommit(theirsId));
            if (walk.next() == null) {
                return com.jfx.git.model.ConflictPrediction.unsupported();
            }
            var merger = (org.eclipse.jgit.merge.ResolveMerger)
                    org.eclipse.jgit.merge.MergeStrategy.RECURSIVE.newMerger(repository(), true);
            if (merger.merge(oursId, theirsId)) {
                return com.jfx.git.model.ConflictPrediction.clean();
            }
            java.util.TreeSet<String> paths = new java.util.TreeSet<>(merger.getUnmergedPaths());
            if (merger.getFailingPaths() != null) paths.addAll(merger.getFailingPaths().keySet());
            return new com.jfx.git.model.ConflictPrediction(true, List.copyOf(paths));
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not predict merging " + theirs + " into " + ours, e);
            return com.jfx.git.model.ConflictPrediction.unsupported();
        }
    }

    /** Message of HEAD, used to prefill the box when amending. */
    public String getHeadCommitMessage() {
        if (repository() == null) return "";
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId head = repository().resolve(Constants.HEAD);
            if (head == null) return "";
            return walk.parseCommit(head).getFullMessage();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read HEAD message", e);
            return "";
        }
    }

    /**
     * Where a commit sits relative to the branch that is checked out, with a single walk.
     *
     * <p>One answer serving several menu items, because they all turn on the same fact
     * and each would otherwise pay for its own history walk. {@code onCurrentBranch} is
     * the one that matters most: the graph draws every branch, so most commits on screen
     * are not on the one HEAD points at. It is checked directly with {@code isMergedInto}
     * rather than by walking history until the commit turns up, which on a large
     * repository means reading all of it to discover the answer is no.
     */
    public CommitPosition getCommitPosition(String hash) {
        if (git() == null) return CommitPosition.unknown();
        try {
            ObjectId id = repository().resolve(hash);
            ObjectId headId = repository().resolve(Constants.HEAD);
            if (id == null || headId == null) {
                return CommitPosition.unknown();
            }
            try (RevWalk walk = new RevWalk(repository())) {
                RevCommit commit = walk.parseCommit(id);
                RevCommit head = walk.parseCommit(headId);
                return new CommitPosition(
                        walk.isMergedInto(commit, head),
                        commit.getParentCount() > 1,
                        commit.getParentCount() == 0,
                        true,
                        isPushed(id));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not locate commit " + hash, e);
            return CommitPosition.unknown();
        }
    }

    /** One commit's message, parsed on its own - the graph page only kept a summary. */
    public String getCommitMessage(String commitHash) {
        if (git() == null) return null;
        // A hash, the same rule the CLI engine applies, not any revision expression.
        if (commitHash == null || !commitHash.matches("[0-9a-fA-F]{4,64}")) return null;
        try {
            ObjectId id = repository().resolve(commitHash + "^{commit}");
            if (id == null) return null;
            try (RevWalk walk = new RevWalk(repository())) {
                return walk.parseCommit(id).getFullMessage();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read the message of " + commitHash, e);
            return null;
        }
    }

    /**
     * Presence-only: JGit does not verify a GPG signature itself, so the only honest
     * answer here is whether a signature blob exists on the commit object at all - see
     * {@link com.jfx.git.model.SignatureStatus} for why that is kept distinct from an
     * actually-verified result rather than reported as {@code GOOD}.
     */
    public com.jfx.git.model.SignatureStatus getSignatureStatus(String commitHash) {
        if (git() == null) return com.jfx.git.model.SignatureStatus.NONE;
        try {
            ObjectId id = repository().resolve(commitHash);
            if (id == null) return com.jfx.git.model.SignatureStatus.NONE;
            try (RevWalk walk = new RevWalk(repository())) {
                RevCommit commit = walk.parseCommit(id);
                return commit.getRawGpgSignature() != null
                        ? com.jfx.git.model.SignatureStatus.SIGNED_UNVERIFIED
                        : com.jfx.git.model.SignatureStatus.NONE;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read signature for " + commitHash, e);
            return com.jfx.git.model.SignatureStatus.NONE;
        }
    }

    /**
     * Whether any remote-tracking branch can reach {@code commitId}.
     *
     * <p>One walk from every {@code refs/remotes/*} tip together, bounded so a huge or
     * unreachable history cannot make a context-menu query expensive: past the limit the
     * answer defaults to "not pushed", which only means a missing warning rather than a
     * wrong action. Annotated tags never appear under refs/remotes, so no peeling.
     */
    private boolean isPushed(ObjectId commitId) {
        final int WALK_LIMIT = 100_000;
        try (RevWalk walk = new RevWalk(repository())) {
            boolean marked = false;
            for (Ref ref : repository().getRefDatabase().getRefsByPrefix(Constants.R_REMOTES)) {
                if (ref.getObjectId() != null) {
                    walk.markStart(walk.parseCommit(ref.getObjectId()));
                    marked = true;
                }
            }
            if (!marked) {
                return false;
            }
            int seen = 0;
            for (RevCommit rc : walk) {
                if (rc.getId().equals(commitId)) {
                    return true;
                }
                if (++seen >= WALK_LIMIT) {
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not determine pushed state", e);
        }
        return false;
    }

    /**
     * Commits in {@code excludeRef..includeRef}, newest first.
     *
     * <p>The general form of {@link #getCommitsSince}, which is the same walk pinned to
     * HEAD. A pull request is proposed from one named branch into another and neither is
     * necessarily checked out, so the range has to take both ends.
     */
    public List<CommitInfo> getCommitsInRange(String excludeRef, String includeRef, int limit) {
        if (git() == null || excludeRef == null || includeRef == null || limit <= 0) {
            return List.of();
        }
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId exclude = repository().resolve(excludeRef);
            ObjectId include = repository().resolve(includeRef);
            if (exclude == null || include == null) {
                return List.of();
            }
            List<CommitInfo> commits = new ArrayList<>();
            for (RevCommit rc : git().log()
                    .addRange(walk.parseCommit(exclude), walk.parseCommit(include))
                    .setMaxCount(limit)
                    .call()) {
                commits.add(revCommitToCommitInfo(rc, List.of()));
            }
            return commits;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Could not list " + excludeRef + ".." + includeRef, e);
            return List.of();
        }
    }

    /**
     * A date-ordered walk from the graph's start points, with the query's criteria as
     * filters - see {@link JGitCommitSearch} for how each one is read.
     *
     * <p>Commit time only, as the graph walk is sorted, so the results come back in the
     * order the CLI engine's {@code git log} gives them. The path goes on as the walk's
     * tree filter, with parent rewriting off: rewriting only matters for drawing lines
     * between the matches, and a flat result list draws none.
     */
    public List<CommitInfo> searchCommits(com.jfx.git.model.CommitSearch query, int limit) {
        if (repository() == null || query == null || query.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<CommitInfo> found = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository())) {
            walk.sort(org.eclipse.jgit.revwalk.RevSort.COMMIT_TIME_DESC, true);
            List<RefInfo> refs = new ArrayList<>();
            refs.addAll(branches().getLocalBranches());
            refs.addAll(branches().getRemoteBranches());
            refs.addAll(branches().getTags());
            // ALL rather than the user's Hide/Solo: a search is asked of the repository,
            // and a hidden branch's commits are exactly the ones a user may be hunting.
            // From every stash's base too: the graph draws the commit a stash was made on
            // even when no branch reaches it, so the search has to reach it as well. A
            // hidden stash's included, for the hidden branch's reason - picking a result
            // the graph does not show puts its details on the right and says why. Nothing
            // is hidden, so what reaches a commit is never asked.
            for (String start : com.jfx.git.graph.GraphPageBuilder.startPoints(
                    refs, com.jfx.git.graph.GraphVisibility.ALL, getHeadHash(), stashes().getStashEntries(),
                    (hash, from) -> false)) {
                markStartByHash(walk, start);
            }
            var paths = JGitCommitSearch.pathFilter(query);
            if (paths != null) {
                walk.setTreeFilter(org.eclipse.jgit.treewalk.filter.AndTreeFilter.create(
                        paths, org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF));
                walk.setRewriteParents(false);
            }
            walk.setRevFilter(JGitCommitSearch.revFilter(query, paths,
                    repository().getConfig().get(org.eclipse.jgit.diff.DiffConfig.KEY)));
            for (RevCommit rc : walk) {
                found.add(revCommitToSummary(rc, List.of()));
                if (found.size() >= limit || GitTaskRunner.isCancelled()) break;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not search history for " + query.describe(), e);
        }
        return found;
    }

    /** See {@link JGitGrep}. */
    public List<com.jfx.git.model.GrepMatch> grep(String revOrNull, com.jfx.git.model.GrepQuery query, int limit) {
        return JGitGrep.grep(repository(), revOrNull, query, limit);
    }

    public List<CommitInfo> getCommitsSince(String ancestorHash) {
        if (git() == null || ancestorHash == null) return List.of();
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId ancestorId = repository().resolve(ancestorHash);
            ObjectId headId = repository().resolve(Constants.HEAD);
            if (ancestorId == null || headId == null) return List.of();

            RevCommit ancestor = walk.parseCommit(ancestorId);
            RevCommit head = walk.parseCommit(headId);
            List<CommitInfo> commits = new ArrayList<>();
            for (RevCommit rc : git().log().addRange(ancestor, head).call()) {
                commits.add(revCommitToCommitInfo(rc, List.of()));
            }
            return oldestFirst(commits, headId.getName());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list commits since " + ancestorHash, e);
            return List.of();
        }
    }

    /**
     * The commits of {@code ancestor..HEAD} in replay order, oldest first.
     *
     * <p>Decided by {@link com.jfx.git.graph.CommitOrdering} rather than by either walk:
     * JGit's log is date-ordered and the CLI's was {@code --topo-order}, so with a merge in
     * the range the two engines handed the undo preview and the cherry-pick follow-up
     * different sequences (G16). The canonical order reversed is topological - every
     * parent before its children - and the same on every engine.
     */
    static List<CommitInfo> oldestFirst(List<CommitInfo> newestFirst, String headHash) {
        List<CommitInfo> ordered = new ArrayList<>(com.jfx.git.graph.CommitOrdering.canonical(
                newestFirst, headHash, com.jfx.git.graph.GraphOrder.BY_BRANCH));
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    // ------------------------------------------------------------------
    // Reflog
    // ------------------------------------------------------------------

    /**
     * Every move HEAD has made, most recent first.
     *
     * <p>This is the safety net under everything that rewrites history. A rebase, a reset
     * or an amend leaves the old commits in place and unreferenced; the reflog is the only
     * record of where the branch used to point, and it is what a restore reads.
     */
    public List<ReflogItem> getReflog(int limit) {
        if (git() == null) return List.of();
        try {
            List<ReflogItem> items = new ArrayList<>();
            int index = 0;
            for (org.eclipse.jgit.lib.ReflogEntry entry : git().reflog().call()) {
                if (index >= limit) break;
                var who = entry.getWho();
                items.add(new ReflogItem(
                        index++,
                        entry.getOldId() != null ? entry.getOldId().getName() : null,
                        entry.getNewId() != null ? entry.getNewId().getName() : null,
                        entry.getComment(),
                        who == null ? "" : who.getName(),
                        who == null ? null : who.getWhenAsInstant()));
            }
            return items;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read the reflog", e);
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Graph
    // ------------------------------------------------------------------

    /**
     * Builds the commit graph, newest first, capped at {@code limit} commits.
     *
     * <p>Walking from the beginning each time is deliberate. {@code PlotCommitList}
     * assigns lanes across the whole walk, so a second walk appended to the first would
     * hand the same branch a different lane and colour halfway down the graph. One walk
     * covering everything on screen keeps the lanes stable, and walking a few thousand
     * commits is cheap next to getting that wrong.
     *
     * <p>The previous version called {@code fillTo(Integer.MAX_VALUE)}, which walked the
     * entire history on the FX thread.
     */
    public GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder order) {
        return getCommitGraphPage(limit, order, com.jfx.git.graph.GraphVisibility.ALL);
    }

    public GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder order,
                                         com.jfx.git.graph.GraphVisibility visibility) {
        if (repository() == null) return new GraphPage(List.of(), false);
        if (visibility == null) visibility = com.jfx.git.graph.GraphVisibility.ALL;

        boolean dirty;
        try {
            dirty = staging().hasUncommittedChanges();
        } catch (GitOperationException e) {
            LOGGER.log(Level.WARNING, "Status check failed while building graph", e);
            dirty = false;
        }

        // Read once, and handed back with the page: the refs decide where the walk starts
        // and what decorates each row, and every view this refresh feeds takes them from
        // the page rather than reading them again.
        com.jfx.git.model.RefSnapshot snapshot = readRefSnapshot();
        String headHash = snapshot.headHash();
        List<RefInfo> refs = snapshot.refs();
        var decorations = com.jfx.git.graph.GraphPageBuilder.decorations(
                refs, visibility, headHash, snapshot.currentBranchRef());
        List<com.jfx.git.model.StashEntry> stashes = com.jfx.git.graph.GraphPageBuilder.shownStashes(
                snapshot, com.jfx.git.core.AppPreferences.hiddenStashes());

        List<CommitInfo> walked = new ArrayList<>();
        boolean complete = true;
        java.util.Set<String> reachableFromHead = null;
        try (RevWalk walk = new RevWalk(repository())) {
            // Commit time only, deliberately not TOPO. This walk decides *which* commits
            // the page contains, not what order they are drawn in -- CommitOrdering does
            // that. Selecting by date is a rule the CLI backend can follow exactly (it is
            // git log's own default), so both engines pick the same commits; selecting by
            // a topological walk is not, because each implementation's is different.
            walk.sort(org.eclipse.jgit.revwalk.RevSort.COMMIT_TIME_DESC, true);
            for (String start : com.jfx.git.graph.GraphPageBuilder.startPoints(
                    refs, visibility, headHash, stashes, this::reachedFrom)) {
                markStartByHash(walk, start);
            }

            // Enough for the page's last ordering chunk to be complete - and, when HEAD is
            // older than all of that, far enough to reach HEAD too, so the uncommitted row
            // has its parent on the page.
            int fetch = com.jfx.git.graph.CommitOrdering.fetchCount(limit);
            int reach = fetch + com.jfx.git.graph.CommitOrdering.HEAD_REACH;
            int headAt = -1;
            for (RevCommit rc : walk) {
                int budget = headAt >= 0 || headHash == null ? fetch : reach;
                if (walked.size() >= budget) { complete = false; break; }
                walked.add(revCommitToSummary(rc, decorations.getOrDefault(rc.getName(), List.of())));
                // The page keeps a summary, so the raw commit buffer - message and all -
                // has nothing left to give. Dropped now rather than when the walk closes,
                // it is not held for every commit on the page at once; what the ordering
                // and the second pass below read (parents, commit time) is kept.
                rc.disposeBody();
                if (headAt < 0 && rc.getName().equals(headHash)) {
                    headAt = walked.size() - 1;
                    fetch = Math.max(fetch, com.jfx.git.graph.CommitOrdering.fetchCountThrough(headAt));
                }
            }

            // Second pass on the *same* walk. reset() drops the marks but keeps the
            // object pool, so every commit this one revisits is already parsed - it is
            // pointer-chasing rather than a second read of the object database, which is
            // what a fresh RevWalk here used to cost on every page and every refresh.
            reachableFromHead = reachableFromHead(walk, walked.size());
        } catch (Exception e) {
            // Whatever was walked before the failure is still worth drawing, but it is not
            // the end of the history: left complete, the page lost "Load more" and showed
            // a truncated history as if it were the whole of it.
            complete = false;
            LOGGER.log(Level.WARNING, "Could not build commit graph - showing the "
                    + walked.size() + " commits read before the failure", e);
        }

        return com.jfx.git.graph.GraphPageBuilder.build(com.jfx.git.graph.GraphPageBuilder.Input.of(
                walked, complete, order, limit, dirty, snapshot, stashes, reachableFromHead));
    }

    /**
     * The repository's refs as one read sees them; see {@link com.jfx.git.model.RefSnapshot}.
     *
     * <p>The current branch from HEAD's symbolic target, so a detached HEAD has none and
     * an unborn branch still counts; what each branch tracks from its configuration,
     * remote or local; every stash, hidden ones too.
     */
    com.jfx.git.model.RefSnapshot readRefSnapshot() {
        String headHash = getHeadHash();
        String current = branches().getCurrentBranch();
        List<RefInfo> locals = List.of();
        List<RefInfo> remotes = List.of();
        List<RefInfo> tags = List.of();
        java.util.Map<String, String> upstreams = new java.util.LinkedHashMap<>();
        try {
            locals = branches().getLocalBranches();
            remotes = branches().getRemoteBranches();
            tags = branches().getTags();
            var config = repository().getConfig();
            for (RefInfo local : locals) {
                String upstream = new org.eclipse.jgit.lib.BranchConfig(config, local.shortName())
                        .getTrackingBranch();
                if (upstream != null) upstreams.put(local.fullName(), upstream);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read the refs", e);
        }
        return new com.jfx.git.model.RefSnapshot(current, headHash, locals, remotes, tags, upstreams,
                stashes().getStashEntries(), remotes().getRemoteNames());
    }

    public boolean graphReaches(String hash, com.jfx.git.graph.GraphVisibility visibility) {
        if (repository() == null || hash == null) return false;
        com.jfx.git.model.RefSnapshot snapshot = readRefSnapshot();
        List<com.jfx.git.model.StashEntry> stashes = com.jfx.git.graph.GraphPageBuilder.shownStashes(
                snapshot, com.jfx.git.core.AppPreferences.hiddenStashes());
        return reachedFrom(hash, com.jfx.git.graph.GraphPageBuilder.startPoints(
                snapshot.refs(), visibility, snapshot.headHash(), stashes, this::reachedFrom));
    }

    /**
     * Whether {@code hash} is in the history of any of {@code from} - full ref names or
     * commit hashes; one that does not resolve to a commit reaches nothing. One walk for
     * all of them, cut off below the commit's generation, stopping at the first that
     * reaches it - {@code git for-each-ref --contains}, without listing the refs.
     */
    private boolean reachedFrom(String hash, java.util.Collection<String> from) {
        if (from.isEmpty()) return false;
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(hash));
            List<RevCommit> tips = new ArrayList<>();
            for (String rev : from) {
                try {
                    ObjectId id = repository().resolve(rev);
                    if (id != null) tips.add(walk.parseCommit(id));
                } catch (Exception notACommit) {
                    // A tag on a tree or a blob, or a ref deleted since it was listed.
                }
            }
            return walk.isMergedIntoAnyCommit(commit, tips);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not tell what reaches " + hash, e);
            return false;
        }
    }

    /** Marks a walk start from a hash string, swallowing the lookup failures the loops above did. */
    private static void markStartByHash(RevWalk walk, String hash) {
        if (hash == null) return;
        try {
            walk.markStart(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(hash)));
        } catch (Exception ignored) {}
    }

    public String getHeadHash() {
        if (repository() == null) return null;
        try {
            ObjectId head = repository().resolve(Constants.HEAD);
            return head == null ? null : head.getName();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not resolve HEAD", e);
            return null;
        }
    }

    /**
     * The commits reachable from HEAD, up to {@code limit}.
     *
     * <p>One bounded walk rather than an ancestry test per commit: asking git about each
     * of a thousand commits individually would cost a thousand walks. Commits older than
     * the walk's reach are simply absent, and callers treat absence as "assume on the
     * branch" — the context menu still checks properly before acting on one.
     *
     * <p>Takes the caller's {@link RevWalk} and resets it rather than opening its own.
     * The reset clears the previous traversal's marks and keeps its parsed-object pool,
     * so this pass re-reads nothing the page walk has already read.
     */
    private java.util.Set<String> reachableFromHead(RevWalk walk, int limit) {
        java.util.Set<String> reachable = new java.util.HashSet<>();
        try {
            ObjectId headId = repository().resolve(Constants.HEAD);
            if (headId == null) return reachable;
            walk.reset();
            walk.markStart(walk.parseCommit(headId));
            int seen = 0;
            for (RevCommit rc : walk) {
                reachable.add(rc.getName());
                if (++seen >= limit) break;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not mark current-branch commits", e);
        }
        return reachable;
    }

    /**
     * A graph row's {@link CommitInfo#summary summary} of {@code rc}: the subject and a
     * bounded body preview, cut by the same rule git applies for the CLI engine, so the
     * page holds no message bodies (G15).
     */
    private static CommitInfo revCommitToSummary(RevCommit rc, List<RefInfo> refs) {
        List<String> parentHashes = new ArrayList<>(rc.getParentCount());
        for (RevCommit p : rc.getParents()) parentHashes.add(p.getName());
        var ident = rc.getAuthorIdent();
        return CommitInfo.summaryOf(
                rc.getName(),
                rc.getFullMessage(),
                ident != null ? ident.getName() : "",
                ident != null ? ident.getEmailAddress() : "",
                ident != null ? ident.getWhenAsInstant() : java.time.Instant.EPOCH,
                java.time.Instant.ofEpochSecond(rc.getCommitTime()),
                parentHashes,
                refs);
    }

    /** Converts a JGit RevCommit to a CommitInfo, decorating it with the given refs. */
    static CommitInfo revCommitToCommitInfo(RevCommit rc, List<RefInfo> refs) {
        List<String> parentHashes = new ArrayList<>(rc.getParentCount());
        for (RevCommit p : rc.getParents()) parentHashes.add(p.getName());
        var ident = rc.getAuthorIdent();
        java.time.Instant authored = ident != null ? ident.getWhenAsInstant() : java.time.Instant.EPOCH;
        // getCommitTime() is the committer's clock, read from the raw buffer - the clock
        // the date walk selects by, and so the one CommitOrdering sorts by.
        java.time.Instant committed = java.time.Instant.ofEpochSecond(rc.getCommitTime());
        return new CommitInfo(
                rc.getName(),
                rc.getFullMessage(),
                ident != null ? ident.getName() : "",
                ident != null ? ident.getEmailAddress() : "",
                authored,
                committed,
                parentHashes,
                refs);
    }

    // ------------------------------------------------------------------
    // History / blame / diff
    // ------------------------------------------------------------------

    /**
     * Commits that touched {@code filePath}, newest first, following it across renames.
     *
     * <p>Returns the commits themselves rather than pre-formatted strings, so the history
     * view can diff and blame each one. Each
     * carries the path the file had in that commit ({@link CommitInfo#getHistoryPath()}):
     * blame already followed renames, so a history that stopped at the rename cited
     * commits the list itself did not show (G7), and following them means reaching
     * commits where today's path does not exist.
     *
     * @param limit the most commits to return, or {@code 0} for all of them
     */
    public List<CommitInfo> getFileHistoryCommits(String filePath, int limit) {
        if (repository() == null || filePath == null) return List.of();
        List<CommitInfo> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId head = repository().resolve(Constants.HEAD);
            if (head == null) return List.of();

            // Renames FollowFilter found, oldest-reported last. It swaps itself for a filter
            // on the old path when it finds one and reports the pair here - but during the
            // walk's look-ahead, so a report can arrive after the renaming commit itself has
            // already been returned. The callback's timing is therefore not used to decide
            // which commit a name belongs to; each commit's own tree is.
            java.util.ArrayDeque<org.eclipse.jgit.diff.DiffEntry> renames = new java.util.ArrayDeque<>();
            var follow = org.eclipse.jgit.revwalk.FollowFilter.create(filePath,
                    repository().getConfig().get(org.eclipse.jgit.diff.DiffConfig.KEY));
            follow.setRenameCallback(new org.eclipse.jgit.revwalk.RenameCallback() {
                public void renamed(org.eclipse.jgit.diff.DiffEntry entry) {
                    renames.add(entry);
                }
            });
            walk.setTreeFilter(follow);
            walk.setRewriteParents(false);
            walk.markStart(walk.parseCommit(head));

            String current = filePath;
            for (RevCommit rc : walk) {
                if (limit > 0 && commits.size() >= limit) break;
                // The renaming commit still holds the new name; the first commit that does
                // not is older than the rename, and takes the old one.
                while (!renames.isEmpty() && !containsPath(rc, current)) {
                    var rename = renames.poll();
                    if (rename.getNewPath().equals(current)) current = rename.getOldPath();
                }
                CommitInfo info = revCommitToCommitInfo(rc, List.of());
                info.setHistoryPath(current);
                commits.add(info);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read history for " + filePath, e);
        }
        return commits;
    }

    /** Whether {@code commit}'s tree has an entry at {@code path}. */
    private boolean containsPath(RevCommit commit, String path) {
        try (var tree = org.eclipse.jgit.treewalk.TreeWalk.forPath(repository(), path, commit.getTree())) {
            return tree != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Blame annotation as structured rows.
     *
     * @param startCommit commit to blame from, or {@code null} for the working tree
     */
    public List<BlameLine> getBlameLines(String filePath, String startCommit) {
        if (git() == null) return List.of();
        try {
            var command = git().blame().setFilePath(filePath).setFollowFileRenames(true);
            if (startCommit != null && !startCommit.isBlank()) {
                ObjectId start = repository().resolve(startCommit);
                if (start == null) {
                    // Not the working tree instead: a blame of today's file labelled as
                    // the asked-for commit attributes lines that commit never had.
                    LOGGER.log(Level.FINE, "No commit {0} to blame {1} from",
                            new Object[]{startCommit, filePath});
                    return List.of();
                }
                command.setStartCommit(start);
            }
            BlameResult result = command.call();
            if (result == null) {
                return List.of();
            }
            result.computeAll();

            org.eclipse.jgit.diff.RawText contents = result.getResultContents();
            List<BlameLine> lines = new ArrayList<>(contents.size());
            for (int i = 0; i < contents.size(); i++) {
                RevCommit commit = result.getSourceCommit(i);
                if (commit == null) {
                    lines.add(BlameLine.uncommitted(contents.getString(i)));
                    continue;
                }
                lines.add(new BlameLine(
                        commit.getName().substring(0, 7),
                        authorNameOf(commit),
                        commit.getShortMessage(),
                        authorDateOf(commit),
                        contents.getString(i)));
            }
            return lines;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Blame failed for " + filePath, e);
            return List.of();
        }
    }

    /**
     * An author's name, for a commit whose author record may not be there.
     *
     * <p>{@code getAuthorIdent()} returns null for a commit with a malformed or missing
     * author line - rare, but they exist in imported and machine-generated history, and
     * they are permanent. Dereferenced directly it threw out of the whole blame, whose
     * catch returns an empty list, so a single bad commit anywhere in a file's history
     * blanked the blame for the entire file with nothing on screen to say why. One
     * unknown author on one line is the honest outcome.
     */
    private static String authorNameOf(RevCommit commit) {
        var ident = commit == null ? null : commit.getAuthorIdent();
        String name = ident == null ? null : ident.getName();
        return name == null || name.isBlank() ? "Unknown" : name;
    }

    /**
     * An author's date, to the second as git records it, or {@code null} when the commit
     * does not carry one.
     */
    private static java.time.Instant authorDateOf(RevCommit commit) {
        var ident = commit == null ? null : commit.getAuthorIdent();
        if (ident == null) return null;
        try {
            return ident.getWhenAsInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        } catch (Exception missingDate) {
            return null;
        }
    }

    /**
     * Blame annotation for a working-tree file.
     *
     * <p>Reads the line text straight from the blame result. The previous version paired
     * blame output with the HEAD blob's lines, so a working file with more lines than
     * HEAD walked off the end of the result.
     */
    public String getBlame(String filePath) {
        if (git() == null) return "No repository loaded.";
        try {
            BlameResult result = git().blame().setFilePath(filePath).call();
            if (result == null) {
                return "No blame information found (is the file committed?).";
            }
            result.computeAll();

            org.eclipse.jgit.diff.RawText contents = result.getResultContents();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < contents.size(); i++) {
                RevCommit commit = result.getSourceCommit(i);
                String hash = commit != null ? commit.getName().substring(0, 7) : "-------";
                String author = commit != null ? authorNameOf(commit) : "Uncommitted";
                if (author.length() > 15) {
                    author = author.substring(0, 15);
                }
                sb.append(String.format("%-8s %-15s | %s%n", hash, author, contents.getString(i)));
            }
            return sb.toString();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Blame failed for " + filePath, e);
            return "Error getting blame: " + describe(e);
        }
    }
}
