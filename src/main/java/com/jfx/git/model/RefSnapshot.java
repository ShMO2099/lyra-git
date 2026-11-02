package com.jfx.git.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The repository's refs as one read saw them: the current branch, the branches,
 * remote-tracking branches and tags, what each branch tracks, the stashes and the
 * configured remotes.
 *
 * <h2>Why one read</h2>
 * The graph page's load already reads every ref to decide where the walk starts and what
 * decorates each row; the sidebar then read them all again, and the current branch was
 * read a third time beside the page and held in five places that could briefly disagree.
 * Now the page load returns this snapshot with the page (see ADR 0002), built from the
 * same read that laid the page out, and every view that refresh feeds - graph, sidebar,
 * toolbar, status bar, window title - takes its refs from it.
 *
 * <h2>What it promises</h2>
 * <ul>
 *   <li>A detached HEAD is no current branch: {@link #currentBranch()} is blank, never a
 *       commit hash. {@link #headLabel()} is what the user reads instead.</li>
 *   <li>No remote's symbolic {@code HEAD} ({@code origin/HEAD}) and none of the refs the
 *       app fabricates for itself is among the remote branches.</li>
 *   <li>Every stash is here, hidden ones too: the sidebar lists them all, and the graph
 *       leaves out the ones the user hid.</li>
 * </ul>
 *
 * @param currentBranch the branch HEAD is on, short; blank while HEAD is detached. An
 *                      unborn branch is still the current branch.
 * @param headHash      HEAD's commit, or {@code null} when HEAD is unborn
 * @param localBranches every local branch
 * @param remoteBranches every remote-tracking branch, symbolic {@code HEAD}s left out
 * @param tags          every tag, peeled to the commit it names
 * @param upstreams     local branch's full name to the full name of what it tracks
 * @param stashes       every stash, newest first
 * @param remoteNames   the configured remotes, including any with nothing fetched yet
 */
public record RefSnapshot(String currentBranch,
                          String headHash,
                          List<RefInfo> localBranches,
                          List<RefInfo> remoteBranches,
                          List<RefInfo> tags,
                          Map<String, String> upstreams,
                          List<StashEntry> stashes,
                          List<String> remoteNames) {

    /** No refs at all: a repository not read yet, or a page built without a repository. */
    public static final RefSnapshot EMPTY = new RefSnapshot("", null, List.of(), List.of(), List.of(),
            Map.of(), List.of(), List.of());

    public RefSnapshot {
        currentBranch = currentBranch == null ? "" : currentBranch;
        localBranches = localBranches == null ? List.of() : List.copyOf(localBranches);
        remoteBranches = remoteBranches == null ? List.of() : List.copyOf(remoteBranches);
        tags = tags == null ? List.of() : List.copyOf(tags);
        upstreams = upstreams == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(upstreams));
        stashes = stashes == null ? List.of() : List.copyOf(stashes);
        remoteNames = remoteNames == null ? List.of() : List.copyOf(remoteNames);
    }

    /** Whether HEAD is detached: on a commit, with no current branch. */
    public boolean detached() {
        return currentBranch.isEmpty() && headHash != null;
    }

    /** The current branch's full name, {@code refs/heads/main}, or {@code null} while detached. */
    public String currentBranchRef() {
        return currentBranch.isEmpty() ? null : RefNames.localFull(currentBranch);
    }

    /** What the user reads for where HEAD is; see {@link RefNames#headLabel}. */
    public String headLabel() {
        return RefNames.headLabel(currentBranch, headHash);
    }

    /** Every branch, remote-tracking branch and tag, in that order. */
    public List<RefInfo> refs() {
        List<RefInfo> all = new ArrayList<>(localBranches.size() + remoteBranches.size() + tags.size());
        all.addAll(localBranches);
        all.addAll(remoteBranches);
        all.addAll(tags);
        return all;
    }

    /** The local branches' short names. */
    public List<String> localBranchNames() {
        return names(localBranches);
    }

    /** The remote-tracking branches' short names, {@code origin/main}. */
    public List<String> remoteBranchNames() {
        return names(remoteBranches);
    }

    /** The tags' names. */
    public List<String> tagNames() {
        return names(tags);
    }

    /**
     * Local branch to what it tracks, both short - {@code main} to {@code origin/main} - for
     * every branch that tracks something, a local branch included.
     */
    public Map<String, String> upstreamShortNames() {
        Map<String, String> shortNames = new LinkedHashMap<>();
        upstreams.forEach((local, upstream) -> {
            if (local != null && upstream != null) {
                shortNames.put(RefNames.shortName(local), RefNames.shortName(upstream));
            }
        });
        return shortNames;
    }

    /** The remote {@code remoteBranch} belongs to; see {@link RefNames#remoteOf}. */
    public String remoteOf(String remoteBranch) {
        return RefNames.remoteOf(remoteBranch, remoteNames);
    }

    /** {@code remoteBranch}'s branch part; see {@link RefNames#branchOf}. */
    public String branchOf(String remoteBranch) {
        return RefNames.branchOf(remoteBranch, remoteNames);
    }

    /** This snapshot with {@code stashes} in place of its own. */
    public RefSnapshot withStashes(List<StashEntry> stashes) {
        return new RefSnapshot(currentBranch, headHash, localBranches, remoteBranches, tags, upstreams,
                stashes, remoteNames);
    }

    private static List<String> names(Collection<RefInfo> refs) {
        return refs.stream().map(RefInfo::shortName).toList();
    }
}
