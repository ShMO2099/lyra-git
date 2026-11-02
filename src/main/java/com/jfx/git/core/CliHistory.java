package com.jfx.git.core;

import com.jfx.git.graph.GraphPage;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.CommitPosition;
import com.jfx.git.model.RefInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The commit graph, commit search, file history, blame, reflog and single-commit
 * queries on the native engine.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliHistory {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private final CliRepo repo;

    CliHistory(CliRepo repo) {
        this.repo = repo;
    }

    public List<CommitInfo> getCommitsSince(String ancestorHash) {
        repo.ensureOpen();
        if (ancestorHash == null) return List.of();
        // Oldest first, matching GitService's promise: RebaseTodo.fromOldestFirst wants
        // replay order. Ordered by the same rule as the JGit engine rather than by
        // --topo-order: the two disagreed as soon as the range held a merge (G16).
        var head = repo.runner.run("rev-parse", "--verify", "--quiet", "HEAD^{commit}");
        if (!head.isOk()) return List.of();
        String headHash = head.trimmedStdout();
        var res = repo.runner.run(GitCommand.of("log", "--no-show-signature", "--no-decorate", COMMIT_FORMAT)
                .revs(ancestorHash + ".." + headHash).args());
        if (!res.isOk()) return List.of();
        return GitService.oldestFirst(CliParsers.parseCommits(res.stdout()), headHash);
    }

    /** Commits in {@code excludeRef..includeRef}, newest first - git's own range syntax. */
    public List<CommitInfo> getCommitsInRange(String excludeRef, String includeRef, int limit) {
        repo.ensureOpen();
        if (excludeRef == null || includeRef == null || limit <= 0) return List.of();
        var res = repo.runner.run(GitCommand.of("log", "--topo-order", "-n", String.valueOf(limit),
                "--no-decorate", COMMIT_FORMAT).revs(excludeRef + ".." + includeRef).args());
        if (!res.isOk()) return List.of();
        return new ArrayList<>(CliParsers.parseCommits(res.stdout()));
    }

    /**
     * {@code git log} with the query as its limiting options, from the graph's start
     * points on standard input.
     *
     * <p>{@code -F -i --all-match} make every text criterion a literal, case-insensitive
     * match that must hold alongside the others - the {@link
     * com.jfx.git.model.CommitSearch} contract. Content is {@code -G} with the text
     * escaped out of regular-expression syntax (pickaxe ignores {@code -F}), and {@code -M}
     * so a file that only moved is not read as every line removed and added again.
     *
     * <p>git has no option for a hash prefix, so a query with one is answered in two
     * steps: the other criteria select hashes only - cheap output however long the history
     * - the prefix is applied here, and only the survivors are formatted in full.
     */
    public List<CommitInfo> searchCommits(com.jfx.git.model.CommitSearch query, int limit) {
        repo.ensureOpen();
        if (query == null || query.isEmpty() || limit <= 0) return List.of();

        com.jfx.git.model.RefSnapshot snapshot = readRefSnapshot();
        // ALL, as the JGit engine does: a search is asked of the repository, not the view;
        // and from every stash's base, hidden stashes too, as the graph is drawn, so a
        // commit only a stash reaches can be found. Nothing is hidden, so what reaches a
        // commit is never asked.
        List<String> starts = com.jfx.git.graph.GraphPageBuilder.startPoints(
                snapshot.refs(), com.jfx.git.graph.GraphVisibility.ALL, snapshot.headHash(), snapshot.stashes(),
                (hash, from) -> false);
        if (starts.isEmpty()) return List.of();

        List<String> args = new ArrayList<>(List.of("log", "--no-show-signature", "--no-decorate"));
        args.addAll(CliParsers.searchLimitingArguments(query));
        if (query.hashPrefix() == null) {
            args.addAll(List.of("-n", String.valueOf(limit), GRAPH_FORMAT, "--stdin"));
            addPathspec(args, query);
            var res = repo.runner.runWithInput(CliRepo.revisionsInput(starts), args);
            if (!res.isOk()) throw CliRepo.queryFailed("Search", res);
            return CliParsers.parseCommits(res.stdout(), null, true);
        }

        args.addAll(List.of("--format=%H", "--stdin"));
        addPathspec(args, query);
        var hashes = repo.runner.runWithInput(CliRepo.revisionsInput(starts), args);
        if (!hashes.isOk()) throw CliRepo.queryFailed("Search", hashes);
        List<String> matching = new ArrayList<>();
        for (String line : hashes.stdout().split(CliParsers.LINE_BREAK)) {
            String hash = line.trim();
            if (hash.startsWith(query.hashPrefix())) {
                matching.add(hash);
                if (matching.size() >= limit) break;
            }
        }
        if (matching.isEmpty()) return List.of();
        // --no-walk=unsorted shows exactly these, in the order given - the walk's order.
        var res = repo.runner.runWithInput(CliRepo.revisionsInput(matching), List.of(
                "log", "--no-show-signature", "--no-decorate", "--no-walk=unsorted",
                GRAPH_FORMAT, "--stdin"));
        if (!res.isOk()) {
            LOGGER.warning("git log failed formatting search results: " + res.stderr());
            return List.of();
        }
        return CliParsers.parseCommits(res.stdout(), null, true);
    }

    /**
     * {@code git grep -n -I --null}, the path filter as pathspecs with their magic turned
     * back on ({@link NativeGitRunner#PATHSPEC_MAGIC}) - a glob is what the user typed -
     * and an exclude as {@code :(exclude)}. Exit 1 is git's "nothing matched".
     */
    public List<com.jfx.git.model.GrepMatch> grep(String revOrNull, com.jfx.git.model.GrepQuery query, int limit) {
        repo.ensureOpen();
        if (query == null || query.isEmpty() || limit <= 0) return List.of();
        GitCommand command = GitCommand.of("grep", "-n", "-I", "--null", "--no-color",
                        query.regex() ? "-E" : "-F")
                .optsIf(!query.matchCase(), "-i")
                .optsIf(query.wholeWord(), "-w")
                .optsIf(revOrNull == null, "--untracked")
                .opts("-e", query.text());
        if (revOrNull != null) command.revs(revOrNull);
        List<String> pathspecs = new ArrayList<>();
        for (String pattern : query.pathPatterns()) {
            pathspecs.add(pattern.startsWith("!") ? ":(exclude)" + pattern.substring(1) : pattern);
        }
        command.paths(pathspecs).endOfRevisions();
        var res = repo.runner.runWithEnvironment(NativeGitRunner.PATHSPEC_MAGIC, command.args());
        if (res.exitCode() == 1 && (res.stderr() == null || res.stderr().isBlank())) return List.of();
        if (!res.isOk()) throw CliRepo.queryFailed("Search in files", res);
        return CliParsers.parseGrep(res.stdout(), revOrNull, limit);
    }

    /** The path criterion; literal, since the runner sets {@code GIT_LITERAL_PATHSPECS}. */
    private static void addPathspec(List<String> args, com.jfx.git.model.CommitSearch query) {
        if (query.path() != null) {
            args.add("--");
            args.add(query.path());
        }
    }

    public String mergeBase(String commitA, String commitB) {
        repo.ensureOpen();
        var res = repo.runner.run(GitCommand.of("merge-base").revs(commitA, commitB).args());
        String base = res.trimmedStdout();
        return res.isOk() && !base.isEmpty() ? base : null;
    }

    /**
     * {@code git merge-tree --write-tree}: the real merge machinery (ort), run without a
     * working tree. Exit 0 is a clean merge, 1 a conflicted one with the files listed after
     * the result tree; anything else - unrelated histories, a revision that does not
     * resolve - is no answer.
     *
     * <p>Only from git 2.38, which added {@code --write-tree}; the older trivial-merge mode
     * reports conflicts in a format nobody should parse, so an older git is "cannot say".
     */
    public com.jfx.git.model.ConflictPrediction predictMergeConflicts(String ours, String theirs) {
        repo.ensureOpen();
        if (ours == null || theirs == null || !hasMergeTreeWriteTree()) {
            return com.jfx.git.model.ConflictPrediction.unsupported();
        }
        var res = repo.runner.run(GitCommand.of("merge-tree", "--write-tree", "--name-only",
                "--no-messages", "-z").revs(ours, theirs).args());
        String[] fields = res.stdout().split("\0");
        // A revision that does not resolve also exits 1 - with no result tree, which is
        // what tells it from a conflicted merge.
        boolean merged = fields.length > 0 && fields[0].strip().matches("[0-9a-f]{40,64}");
        if (!merged || (res.exitCode() != 0 && res.exitCode() != 1)) {
            return com.jfx.git.model.ConflictPrediction.unsupported();
        }
        if (res.exitCode() == 0) return com.jfx.git.model.ConflictPrediction.clean();
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        // The first field is the result tree; the conflicted files follow, one per field,
        // up to the empty field that would start the (suppressed) messages section.
        for (int i = 1; i < fields.length; i++) {
            if (fields[i].isEmpty()) break;
            paths.add(fields[i]);
        }
        return new com.jfx.git.model.ConflictPrediction(true, List.copyOf(paths));
    }

    /** Whether the installed git has {@code merge-tree --write-tree} (2.38 and later). */
    static boolean hasMergeTreeWriteTree() {
        int[] version = NativeGitRunner.parseVersion(NativeGitRunner.getGitVersion());
        return version != null && NativeGitRunner.compareVersions(version, new int[]{2, 38}) >= 0;
    }

    /**
     * {@code %B} keeps the body; the subject is derived from it by {@link CommitInfo}.
     *
     * <p>No {@code %D}. Nothing has read decorations from a log since the graph took them
     * from one {@code for-each-ref} (see {@link #GRAPH_FORMAT}), yet the range queries
     * still asked git for them - with {@code --decorate=full}, which loads every ref - and
     * threw the answer away. Commits from these queries carry no refs, as before.
     */
    static final String COMMIT_FORMAT =
            "--format=%x1e%H%x1f%B%x1f%an%x1f%ae%x1f%at%x1f%P%x1f%ct";

    /**
     * The graph's format: {@link #COMMIT_FORMAT} with the subject in place of the whole
     * message, and a preview of the body after the commit time.
     *
     * <p>Decorations come from one {@code for-each-ref} instead, the way the JGit engine
     * builds them. Parsing {@code %D} split on commas and so broke legal ref names like
     * {@code fix,typo} (G9); it also decorated with {@code refs/stash}, notes and anything
     * else under {@code refs/}, which the fallback then dressed up as remote branches
     * (G1), and it had no way to leave a hidden ref off.
     *
     * <p>No {@code %B}: a page is re-read on every refresh and "load more", and whole
     * bodies were most of what came back - text no row draws (G15). A row shows its
     * subject and, dimmed after it, as much of the body as fits, so git cuts the body to
     * {@link CommitInfo#BODY_PREVIEW_COLUMNS} columns itself. {@code %<(N,trunc)} pads a
     * shorter body with spaces, which the parser trims; that is a bounded cost, where the
     * whole body was not. Whatever needs the full message fetches it for one commit
     * ({@link #getCommitMessage}).
     */
    static final String GRAPH_FORMAT =
            "--format=%x1e%H%x1f%s%x1f%an%x1f%ae%x1f%at%x1f%P%x1f%ct%x1f%<("
                    + CommitInfo.BODY_PREVIEW_COLUMNS + ",trunc)%b";

    /** {@link #COMMIT_FORMAT} plus a trailing separator, after which {@code --name-status} lands. */
    private static final String FILE_HISTORY_FORMAT = COMMIT_FORMAT + "%x1f";

    /**
     * The repository's refs as one read sees them; see {@link com.jfx.git.model.RefSnapshot}.
     *
     * <p>Every branch, remote-tracking branch and tag in one {@code for-each-ref}, with each
     * branch's upstream, a mark on the one HEAD is on and whether the ref is symbolic -
     * one call rather than three listings plus a {@code symbolic-ref}, since this runs on
     * every refresh. Synthetic remote refs and symbolic ones ({@code origin/HEAD}) are
     * dropped here, as {@link CliBranches#getRemoteBranches} does.
     *
     * <p>No ref marked as HEAD's means HEAD is detached - no current branch - or unborn;
     * only the unborn case, where HEAD names a branch with no commit yet, costs a
     * {@code symbolic-ref} to learn that branch's name.
     */
    com.jfx.git.model.RefSnapshot readRefSnapshot() {
        var res = repo.runner.run("for-each-ref",
                "--format=%(refname)\t%(objectname)\t%(*objectname)\t%(upstream)\t%(HEAD)\t%(symref)",
                "refs/heads/", "refs/remotes/", "refs/tags/");
        List<RefInfo> locals = new ArrayList<>();
        List<RefInfo> remotes = new ArrayList<>();
        List<RefInfo> tags = new ArrayList<>();
        Map<String, String> upstreams = new java.util.LinkedHashMap<>();
        String current = null;
        if (res.isOk()) {
            for (String line : res.stdout().split(CliParsers.LINE_BREAK)) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length < 2) continue;
                String full = parts[0];
                if (GitBackend.isSyntheticRemoteRef(full)) continue;
                String peeled = parts.length > 2 ? parts[2].trim() : "";
                String upstream = parts.length > 3 ? parts[3].trim() : "";
                boolean head = parts.length > 4 && parts[4].trim().equals("*");
                boolean symbolic = parts.length > 5 && !parts[5].trim().isEmpty();
                RefInfo ref = new RefInfo(full, RefInfo.shortNameOf(full), peeled.isEmpty() ? parts[1].trim() : peeled);
                if (ref.isLocalBranch()) {
                    locals.add(ref);
                    if (!upstream.isEmpty()) upstreams.put(full, upstream);
                    if (head) current = ref.shortName();
                } else if (ref.isRemoteBranch()) {
                    if (!symbolic) remotes.add(ref);
                } else if (ref.isTag()) {
                    tags.add(ref);
                }
            }
        }
        var headRes = repo.runner.run("rev-parse", "--verify", "--quiet", "HEAD^{commit}");
        String headHash = headRes.isOk() && !headRes.trimmedStdout().isEmpty() ? headRes.trimmedStdout() : null;
        if (current == null) {
            current = headHash != null && res.isOk() ? "" : repo.branches.getCurrentBranch();
        }
        return new com.jfx.git.model.RefSnapshot(current, headHash, locals, remotes, tags, upstreams,
                repo.stashes.getStashEntries(), repo.remotes.getRemoteNames());
    }

    public boolean graphReaches(String hash, com.jfx.git.graph.GraphVisibility visibility) {
        repo.ensureOpen();
        if (hash == null) return false;
        com.jfx.git.model.RefSnapshot snapshot = readRefSnapshot();
        List<com.jfx.git.model.StashEntry> stashes = com.jfx.git.graph.GraphPageBuilder.shownStashes(
                snapshot, com.jfx.git.core.AppPreferences.hiddenStashes());
        return reachedFrom(hash, com.jfx.git.graph.GraphPageBuilder.startPoints(
                snapshot.refs(), visibility, snapshot.headHash(), stashes, this::reachedFrom));
    }

    /**
     * Whether {@code hash} is in the history of any of {@code from} - full ref names or
     * commit hashes: {@code git rev-list -1 <hash> --not <from...>} prints nothing exactly
     * when one of them reaches it. What asks, and why, is on the JGit engine's
     * {@code reachedFrom}. On standard input, so a graph's thousands of tag tips do not
     * overflow the command line.
     *
     * <p>Not {@code --ignore-missing}: it would drop a {@code hash} that names nothing
     * too, and with nothing left to list, git's empty answer would read as "reached". A
     * name git cannot resolve fails the question instead, which answers false.
     */
    private boolean reachedFrom(String hash, java.util.Collection<String> from) {
        if (from.isEmpty()) return false;
        List<String> input = new ArrayList<>(from.size() + 1);
        input.add(hash);
        for (String rev : from) input.add("^" + rev);
        var res = repo.runner.runWithInput(CliRepo.revisionsInput(input), List.of("rev-list", "-1", "--stdin"));
        return res.isOk() && res.trimmedStdout().isEmpty();
    }

    public GraphPage getCommitGraphPage(int limit,
                                                   com.jfx.git.graph.GraphOrder order) {
        return getCommitGraphPage(limit, order, com.jfx.git.graph.GraphVisibility.ALL);
    }

    /**
     * One page of the graph.
     *
     * <p>The walk starts from exactly what the JGit engine starts from - HEAD when it
     * resolves, then every branch, remote and tag Hide/Solo leaves standing - never
     * {@code --all}, which also walked stash internals, notes and {@code refs/lyra/*}
     * (G1), and never a literal {@code HEAD} argument, which fails on an unborn branch and
     * emptied the graph of every orphan branch (G8). The start points are hashes on
     * standard input, so the command line does not grow with the number of refs and
     * cannot hit Windows' 32K limit however many tags a repository has (G8, N11).
     *
     * <p>No {@code --topo-order}: git log's default is reverse chronological by commit
     * date, which is the selection rule JGit's walk uses too. The drawn order is decided
     * by {@link com.jfx.git.graph.CommitOrdering} afterwards, so both engines draw the
     * same graph.
     */
    public GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder order,
                                                    com.jfx.git.graph.GraphVisibility visibility) {
        repo.ensureOpen();
        if (visibility == null) visibility = com.jfx.git.graph.GraphVisibility.ALL;
        // A failed status check costs the uncommitted row, not the whole graph: the
        // history is still readable, and an empty graph would say the repository has none.
        // Same choice as the JGit engine.
        boolean dirty;
        try {
            dirty = repo.staging.hasUncommittedChanges();
        } catch (GitOperationException e) {
            LOGGER.log(Level.WARNING, "Status check failed while building graph", e);
            dirty = false;
        }

        // Read once, and handed back with the page: the refs decide where the walk starts
        // and what decorates each row, and every view this refresh feeds takes them from
        // the page rather than reading them again.
        com.jfx.git.model.RefSnapshot snapshot = readRefSnapshot();
        List<RefInfo> refs = snapshot.refs();
        String headHash = snapshot.headHash();

        var decorations = com.jfx.git.graph.GraphPageBuilder.decorations(
                refs, visibility, headHash, snapshot.currentBranchRef());
        List<com.jfx.git.model.StashEntry> stashes = com.jfx.git.graph.GraphPageBuilder.shownStashes(
                snapshot, com.jfx.git.core.AppPreferences.hiddenStashes());
        List<String> starts = com.jfx.git.graph.GraphPageBuilder.startPoints(
                refs, visibility, headHash, stashes, this::reachedFrom);

        List<CommitInfo> walked = List.of();
        boolean complete = true;
        if (!starts.isEmpty()) {
            int fetch = com.jfx.git.graph.CommitOrdering.fetchCount(limit);
            GraphWalk walk = walkGraph(starts, fetch, decorations);
            if (walk == null) {
                return com.jfx.git.graph.GraphPageBuilder.build(com.jfx.git.graph.GraphPageBuilder.Input.of(
                        List.of(), false, order, limit, dirty, snapshot, List.of(), null));
            }
            // HEAD belongs on the page (the uncommitted row hangs off it) even when it is
            // older than everything a normal page walks. Rare - an old branch or tag
            // checked out - so it costs a hashes-only rev-list and one more log only then.
            if (headHash != null && !walk.complete()) {
                int at = indexOfHash(walk.commits(), headHash);
                if (at < 0) {
                    at = positionInWalk(starts, headHash,
                            fetch + com.jfx.git.graph.CommitOrdering.HEAD_REACH);
                }
                int needed = at < 0 ? -1 : com.jfx.git.graph.CommitOrdering.fetchCountThrough(at);
                if (needed > fetch) {
                    GraphWalk further = walkGraph(starts, needed, decorations);
                    if (further != null) walk = further;
                }
            }
            walked = walk.commits();
            complete = walk.complete();
        }

        List<String> fromHead = headHash == null ? List.of() : revListFrom(headHash, walked.size());
        return com.jfx.git.graph.GraphPageBuilder.build(com.jfx.git.graph.GraphPageBuilder.Input.of(
                walked, complete, order, limit, dirty, snapshot, stashes, new LinkedHashSet<>(fromHead)));
    }

    /** What one graph walk returned, and whether it reached the end of history. */
    private record GraphWalk(List<CommitInfo> commits, boolean complete) {
    }

    /**
     * The first {@code count} commits of the date-ordered walk from {@code starts}, or
     * {@code null} when git failed. One more is asked for than returned, so a history of
     * exactly {@code count} commits is known to be complete.
     */
    private GraphWalk walkGraph(List<String> starts, int count, Map<String, List<RefInfo>> decorations) {
        var res = repo.runner.runWithInput(CliRepo.revisionsInput(starts), List.of(
                "log", "--no-show-signature", "--no-decorate", "-n", String.valueOf(count + 1),
                GRAPH_FORMAT, "--stdin"));
        if (!res.isOk()) {
            LOGGER.warning("git log failed: " + res.stderr());
            return null;
        }
        List<CommitInfo> commits = CliParsers.parseCommits(res.stdout(), decorations, true);
        boolean complete = commits.size() <= count;
        if (!complete) commits = new ArrayList<>(commits.subList(0, count));
        return new GraphWalk(commits, complete);
    }

    /** Where {@code hash} falls in the walk from {@code starts}, hashes only; -1 if not within {@code cap}. */
    private int positionInWalk(List<String> starts, String hash, int cap) {
        var res = repo.runner.runWithInput(CliRepo.revisionsInput(starts), List.of(
                "rev-list", "-n", String.valueOf(cap), "--stdin"));
        if (!res.isOk()) return -1;
        String[] lines = res.stdout().split(CliParsers.LINE_BREAK);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(hash)) return i;
        }
        return -1;
    }

    private static int indexOfHash(List<CommitInfo> commits, String hash) {
        for (int i = 0; i < commits.size(); i++) {
            if (commits.get(i).getHash().equals(hash)) return i;
        }
        return -1;
    }

    /**
     * The commits {@code head} reaches, newest first and capped at {@code limit}.
     *
     * <p>Named by hash rather than as {@code HEAD}, so it cannot disagree with the page
     * about which commit HEAD was if HEAD moves in between.
     */
    private List<String> revListFrom(String head, int limit) {
        if (limit <= 0) return List.of();
        var res = repo.runner.run(GitCommand.of("rev-list", "-n", String.valueOf(limit)).revs(head).args());
        if (!res.isOk()) return List.of();
        List<String> hashes = new ArrayList<>();
        for (String line : res.trimmedStdout().split("\\r?\\n")) {
            if (!line.isBlank()) hashes.add(line.trim());
        }
        return hashes;
    }

    /**
     * Where {@code hash} sits relative to HEAD.
     *
     * <p>Answered by asking git rather than assuming: the previous version returned a
     * fixed "on the current branch, not a merge, not a root" for every commit, which made
     * the graph's revert and cherry-pick entries agree with reality only by luck.
     */
    public CommitPosition getCommitPosition(String hash) {
        repo.ensureOpen();
        var parents = repo.runner.run(GitCommand.of("rev-list", "--parents", "-n", "1").revs(hash).args());
        if (!parents.isOk() || parents.trimmedStdout().isBlank()) {
            return CommitPosition.unknown();
        }

        // "<commit> <parent>..." — one field means a root commit, three or more a merge.
        String[] ids = parents.trimmedStdout().split("\\s+");
        boolean root = ids.length == 1;
        boolean merge = ids.length > 2;

        // 0 is "an ancestor", 1 is "not one"; anything else is git failing to answer.
        var ancestry = repo.runner.run(GitCommand.of("merge-base", "--is-ancestor").revs(hash, "HEAD").args());
        if (ancestry.exitCode() != 0 && ancestry.exitCode() != 1) {
            return CommitPosition.unknown();
        }
        boolean onCurrentBranch = ancestry.exitCode() == 0;

        // Any remote-tracking ref that contains the commit means it has been pushed. A
        // check that fails is not a "no": `pushed` is what puts the force-push warning in
        // front of a reword or drop, and reading a failure as "not pushed" rewrote
        // published history with no warning at all. Unknown errs towards the warning.
        var remotes = repo.runner.run("for-each-ref", "--contains", hash, "--count=1",
                "--format=%(refname)", "refs/remotes/");
        if (!remotes.isOk()) {
            return new CommitPosition(onCurrentBranch, merge, root, true, true);
        }
        boolean pushed = !remotes.trimmedStdout().isBlank();

        return new CommitPosition(onCurrentBranch, merge, root, true, pushed);
    }

    /**
     * {@code %B} for one commit, verbatim. The format is a terminator format, so git ends
     * the record with one newline of its own; that one - and only that one - is removed,
     * which keeps a message's own trailing newline, the way JGit reports it.
     */
    public String getCommitMessage(String commitHash) {
        repo.ensureOpen();
        // A hash, never an option: this goes on a command line.
        if (commitHash == null || !commitHash.matches("[0-9a-fA-F]{4,64}")) return null;
        var res = repo.runner.run(GitCommand.of("log", "-1", "--no-show-signature", "--format=%B")
                .revs(commitHash).endOfRevisions().args());
        if (!res.isOk()) return null;
        String out = res.stdout();
        return out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
    }

    /**
     * Full detail, unlike JGit: native git calls the user's own {@code gpg} to verify
     * the signature, so {@code %G?} answers with the actual trust result rather than
     * just "a signature is present". See {@link com.jfx.git.model.SignatureStatus}.
     */
    public com.jfx.git.model.SignatureStatus getSignatureStatus(String commitHash) {
        repo.ensureOpen();
        var res = repo.runner.run(GitCommand.of("log", "-1", "--format=%G?")
                .revs(commitHash).endOfRevisions().args());
        if (!res.isOk()) return com.jfx.git.model.SignatureStatus.NONE;
        return switch (res.trimmedStdout()) {
            case "G" -> com.jfx.git.model.SignatureStatus.GOOD;
            case "B" -> com.jfx.git.model.SignatureStatus.BAD;
            case "U" -> com.jfx.git.model.SignatureStatus.GOOD_UNTRUSTED;
            case "X" -> com.jfx.git.model.SignatureStatus.GOOD_EXPIRED;
            case "Y" -> com.jfx.git.model.SignatureStatus.GOOD_EXPIRED;
            case "R" -> com.jfx.git.model.SignatureStatus.GOOD_REVOKED_KEY;
            case "E" -> com.jfx.git.model.SignatureStatus.CANNOT_VERIFY;
            default -> com.jfx.git.model.SignatureStatus.NONE; // "N", or anything unrecognised
        };
    }

    /**
     * Commits that touched {@code filePath}, newest first, following it across renames.
     *
     * <p>{@code --follow --name-status}, as sourcegit does it, so each commit also says
     * what the file was called there ({@link CommitInfo#getHistoryPath()}). Without
     * {@code --follow} the list stopped at the rename while blame - which does follow -
     * went on citing older commits the list never showed (G7). {@code -z} keeps paths
     * unquoted whatever {@code core.quotepath} says.
     *
     * @param limit the most commits to return, or {@code 0} for all of them
     */
    public List<CommitInfo> getFileHistoryCommits(String filePath, int limit) {
        repo.ensureOpen();
        if (filePath == null) return List.of();
        var res = repo.runner.run(GitCommand.of("log", "--no-show-signature", "--no-decorate",
                        "--follow", "--name-status", "-z", FILE_HISTORY_FORMAT)
                .optsIf(limit > 0, "-n", String.valueOf(limit))
                .paths(filePath).args());
        if (!res.isOk()) throw CliRepo.queryFailed("File history", res);
        return CliParsers.parseFileHistory(res.stdout(), filePath);
    }

    /**
     * The reflog, newest first.
     *
     * <p>Both ends of each move are read: {@code %H} is where HEAD landed and
     * {@code ReflogItem} needs the previous value to tell branch creation apart from an
     * ordinary move. Using the same hash for both, as the first version did, made every
     * entry look like it had somewhere to go back to.
     */
    public List<ReflogItem> getReflog(int limit) {
        repo.ensureOpen();
        // %gn and %gd, not %an and %at: those are the commit's author and author date,
        // so a "reset --hard HEAD~5" made today showed last year's date and someone else's
        // name. With --date=unix, %gd is HEAD@{<when the entry was written>}.
        //
        // One more than asked for, so the last row shown still has the entry before it to
        // say where HEAD moved from. Without it the last row had no old hash, which is
        // what a branch's creation looks like - so the oldest undo step on this engine was
        // always labelled as one.
        //
        // -z and the message last, as the stash list reads it: a reflog message is a
        // commit's subject, which may contain the separators, and one that did split its
        // entry in two and shifted every later HEAD@{n} - the position Undo resets to.
        var res = repo.runner.run("reflog", "-z", "-n", String.valueOf(limit + 1), "--date=unix",
                "--format=%H%x1f%gn%x1f%gd%x1f%gs");
        if (!res.isOk()) return List.of();
        return CliParsers.parseReflog(res.stdout(), limit);
    }

    public List<BlameLine> getBlameLines(String filePath, String startCommit) {
        repo.ensureOpen();
        // Not a GitCommand: blame reads --end-of-options as the start of its revisions and
        // then takes the path after "--" for one too ("bad revision"). So the commit is
        // checked here instead, as checkout's branch is - one that begins with a dash
        // would be read as an option.
        if (startCommit != null && startCommit.startsWith("-")) {
            throw new GitOperationException("Blame failed: a commit cannot start with \"-\": " + startCommit);
        }
        List<String> args = new ArrayList<>(List.of("blame", "--porcelain"));
        if (startCommit != null && !startCommit.isBlank()) args.add(startCommit);
        args.add("--");
        args.add(filePath);
        var res = repo.runner.run(args);
        if (!res.isOk()) throw CliRepo.queryFailed("Blame", res);
        // Untrimmed: a file whose last line is blank ends in a lone "\t" line, and trimming
        // took that line - and so the file's last row of blame - away with the whitespace.
        return CliParsers.parseBlamePorcelain(res.stdout());
    }
}
