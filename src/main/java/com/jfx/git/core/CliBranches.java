package com.jfx.git.core;

import com.jfx.git.model.RefInfo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Branches, tags, checkout and upstream tracking on the native engine.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliBranches {

    private final CliRepo repo;

    CliBranches(CliRepo repo) {
        this.repo = repo;
    }

    public List<RefInfo> getLocalBranches() {
        return getRefsByPrefix("refs/heads/");
    }

    /**
     * Remote-tracking branches: not a remote's symbolic {@code HEAD} ({@code origin/HEAD}),
     * which points at one of the others and is no branch, nor a ref the app fabricated.
     */
    public List<RefInfo> getRemoteBranches() {
        return getRefsByPrefix("refs/remotes/", true).stream()
                .filter(ref -> !GitBackend.isSyntheticRemoteRef(ref.fullName()))
                .toList();
    }

    public List<RefInfo> getTags() {
        return getRefsByPrefix("refs/tags/");
    }

    /**
     * Lists the refs under {@code prefix} as {@link RefInfo}.
     *
     * <p>Both {@code %(objectname)} and its peeled form {@code %(*objectname)} are asked
     * for, and the peeled one wins when present. {@code RefInfo.targetHash} is the key of
     * the graph's {@code refsByHash} decoration map, so an annotated tag reported with
     * the tag <em>object</em>'s SHA - which no commit row carries - decorates nothing,
     * and every piece of tag-keyed logic (chips, hide/solo, jump-to-tag) then silently
     * sees lightweight tags only. {@code %(*objectname)} is empty for anything that is
     * not a tag object, which is exactly when the unpeeled name is already right.
     */
    private List<RefInfo> getRefsByPrefix(String prefix) {
        return getRefsByPrefix(prefix, false);
    }

    /** @param skipSymbolic leave out symbolic refs, such as {@code origin/HEAD} */
    private List<RefInfo> getRefsByPrefix(String prefix, boolean skipSymbolic) {
        repo.ensureOpen();
        // No %(refname:short): it disambiguates, so a branch "main" beside a tag "main"
        // came back as "heads/main" and never matched the current branch (G10). The short
        // name is the full name with its namespace stripped, which is what JGit reports.
        var res = repo.runner.run("for-each-ref",
                "--format=%(refname)\t%(objectname)\t%(*objectname)\t%(symref)", prefix);
        if (!res.isOk()) return List.of();

        List<RefInfo> list = new ArrayList<>();
        for (String line : res.stdout().split("\\r?\\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 4);
            if (skipSymbolic && parts.length >= 4 && !parts[3].trim().isEmpty()) continue;
            if (parts.length >= 2) {
                String peeled = parts.length >= 3 ? parts[2].trim() : "";
                list.add(new RefInfo(parts[0], RefInfo.shortNameOf(parts[0]),
                        peeled.isEmpty() ? parts[1].trim() : peeled));
            }
        }
        return list;
    }

    public String getCurrentBranch() {
        repo.ensureOpen();
        // symbolic-ref rather than `branch --show-current`, which only arrived in 2.22:
        // on anything older it failed, this fell through to the short hash below, and a
        // push then built refs/heads/<hash>. The full name is shortened here rather than
        // with --short, which answers "heads/main" when a tag called main also exists.
        //
        // A detached HEAD is no current branch: blank, not the commit - see GitBackend.
        var res = repo.runner.run("symbolic-ref", "--quiet", "HEAD");
        String ref = res.isOk() ? res.trimmedStdout() : "";
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : "";
    }

    public int[] getTrackingStatus() {
        repo.ensureOpen();
        var res = repo.runner.run("rev-list", "--left-right", "--count", "HEAD...@{upstream}");
        if (res.isOk()) {
            String[] parts = res.trimmedStdout().split("\\s+");
            if (parts.length >= 2) {
                try {
                    return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
                } catch (NumberFormatException ignored) {}
            }
        }
        return new int[]{0, 0};
    }

    public String symbolicRefTarget(String fullName) {
        repo.ensureOpen();
        if (fullName == null || fullName.isBlank() || fullName.startsWith("-")) return null;
        // --quiet: a ref that is not symbolic is an answer (none), not an error to log.
        var res = repo.runner.run("symbolic-ref", "--quiet", fullName);
        String target = res.isOk() ? res.stdout().strip() : "";
        return target.isEmpty() ? null : target;
    }

    public boolean isHeadDetached() {
        repo.ensureOpen();
        // symbolic-ref fails when HEAD is detached; it also fails on an unborn branch,
        // so confirm a commit exists before calling that detached.
        var symbolic = repo.runner.run("symbolic-ref", "--quiet", "HEAD");
        if (symbolic.isOk()) {
            return false;
        }
        return repo.runner.run("rev-parse", "--verify", "--quiet", "HEAD").isOk();
    }

    public boolean isDetachedHeadOrphanRisk() {
        repo.ensureOpen();
        if (!isHeadDetached()) return false;
        // for-each-ref, not `branch --contains`: the latter prints a synthetic
        // "* (HEAD detached from ...)" line while detached, which makes the output
        // non-empty even when no real branch reaches the commit.
        var branches = repo.runner.run("for-each-ref", "--contains", "HEAD", "refs/heads/");
        if (branches.isOk() && !branches.trimmedStdout().isEmpty()) {
            return false;
        }
        var tags = repo.runner.run("for-each-ref", "--contains", "HEAD", "refs/tags/");
        return !(tags.isOk() && !tags.trimmedStdout().isEmpty());
    }

    public GitResult checkoutBranch(String branchName) {
        repo.ensureOpen();
        GitResult rejected = CliRepo.rejectIfOptionLike(branchName, "A branch or commit");
        if (rejected != null) return rejected;
        // The trailing "--" is what makes branchName a revision. Without it, a name that
        // does not resolve - a branch deleted in a terminal while the sidebar still showed
        // it - is read as a pathspec, and git restores those paths from the index: a
        // folder of the same name lost its uncommitted edits, git exited 0, and this
        // reported "Switched to branch". With it, git refuses: "invalid reference".
        var res = repo.runner.run(GitCommand.of("checkout").revs(branchName).endOfRevisions().args());
        if (!res.isOk()) return RefusalWords.failure(res.stderr());
        repo.structure.maybeAutoUpdateSubmodules();
        return GitResult.ok("Switched to branch " + branchName);
    }

    public GitResult checkoutRemoteBranch(String remoteBranch) {
        repo.ensureOpen();
        // What follows the remote, which may have a slash in its own name: team/core/topic
        // is topic, not core/topic.
        String local = com.jfx.git.model.RefNames.branchOf(remoteBranch, repo.remotes.getRemoteNames());
        GitResult rejected = CliRepo.rejectIfOptionLike(local, "A branch name");
        if (rejected != null) return rejected;
        // A local branch of that name usually already exists - the user checked this
        // remote branch out before. `checkout -b` would refuse outright, so switch to the
        // branch they already have rather than reporting "branch already exists" for the
        // ordinary case. Not `-B`: that would move an existing branch onto the remote
        // tip and silently drop local commits.
        boolean exists = !repo.runner.run("rev-parse", "--verify", "--quiet",
                "refs/heads/" + local).trimmedStdout().isEmpty();
        var res = exists
                ? repo.runner.run(GitCommand.of("checkout").revs(local).endOfRevisions().args())
                : repo.runner.run(GitCommand.of("checkout", "-b", local, "--track")
                        .revs(remoteBranch).endOfRevisions().args());
        if (!res.isOk()) return RefusalWords.failure(res.stderr());
        repo.structure.maybeAutoUpdateSubmodules();
        return GitResult.ok(exists
                ? "Checked out existing branch " + local
                : "Created tracking branch " + local + " from " + remoteBranch);
    }

    public GitResult checkoutPullRequest(String remoteName, int number, boolean resetToFetched) {
        repo.ensureOpen();
        String localName = "pr-" + number;
        // Not refs/remotes/<remote>/pr/<n>: no fetch refspec covers it, so it would sit
        // in the sidebar's remote-branch tree and decorate the graph forever.
        String remoteTrackingRef = GitBackend.INTERNAL_REF_PREFIX + "pr/" + remoteName + "/" + number;
        var fetchRes = repo.runNetwork(List.of("fetch", "--", remoteName,
                "+refs/pull/" + number + "/head:" + remoteTrackingRef));
        if (!fetchRes.isOk()) return GitResult.failed(fetchRes.stderr());
        return checkoutFetched(localName, remoteTrackingRef, "pull request #" + number, resetToFetched);
    }

    public GitResult checkoutRemoteRef(String remoteNameOrUrl, String remoteRef, String localBranchName,
                                       boolean resetToFetched) {
        repo.ensureOpen();
        GitResult rejected = CliRepo.rejectIfOptionLike(localBranchName, "A branch name");
        if (rejected != null) return rejected;
        String trackingRef = GitBackend.INTERNAL_REF_PREFIX + "checkout/" + localBranchName;
        var fetchRes = repo.runNetwork(List.of("fetch", "--", remoteNameOrUrl, "+" + remoteRef + ":" + trackingRef));
        if (!fetchRes.isOk()) return GitResult.failed(fetchRes.stderr());
        return checkoutFetched(localBranchName, trackingRef, remoteRef, resetToFetched);
    }

    /**
     * Checks out {@code localName} at what was just fetched into {@code fetchedRef},
     * never discarding the local branch's own commits.
     *
     * <p>This used to be {@code checkout -B}, which silently reset an existing
     * {@code pr-N} onto the fetched head and threw away any fix-up commits made on it.
     * See {@code GitService.checkoutFetched}: both engines fast-forward a branch that is
     * simply behind, leave one that is ahead, and refuse when the two have diverged -
     * unless {@code resetToFetched} says the user has chosen the pull request over the
     * branch, which is the one case {@code checkout -B} is now used for.
     */
    private GitResult checkoutFetched(String localName, String fetchedRef, String what,
                                      boolean resetToFetched) {
        String local = repo.runner.run("rev-parse", "--verify", "--quiet", "refs/heads/" + localName).trimmedStdout();
        if (local.isEmpty()) {
            var created = repo.runner.run(GitCommand.of("checkout", "-b", localName)
                    .revs(fetchedRef).endOfRevisions().args());
            if (!created.isOk()) return RefusalWords.failure(created.stderr());
            repo.structure.maybeAutoUpdateSubmodules();
            return GitResult.ok("Checked out " + what + " as " + localName);
        }
        String fetched = repo.runner.run("rev-parse", "--verify", "--quiet", fetchedRef).trimmedStdout();
        if (fetched.isEmpty()) return GitResult.failed("Checkout failed: nothing was fetched for " + what);
        boolean behind = fetched.equals(local)
                || repo.runner.run(GitCommand.of("merge-base", "--is-ancestor").revs(local, fetched).args()).isOk();
        boolean ahead = !behind && repo.runner.run(GitCommand.of("merge-base", "--is-ancestor")
                .revs(fetched, local).args()).isOk();
        if (!behind && !ahead) {
            if (!resetToFetched) {
                String count = repo.runner.run(GitCommand.of("rev-list", "--count")
                        .revs(fetched + ".." + local).args()).trimmedStdout();
                int localOnly;
                try {
                    localOnly = Integer.parseInt(count);
                } catch (NumberFormatException e) {
                    localOnly = 1;
                }
                return DivergedCheckout.refusal(localName, what, localOnly);
            }
            // Not --force: local changes are carried across or the checkout refuses, as
            // on the JGit engine. The branch's reflog keeps the commits it moved off.
            var reset = repo.runner.run(GitCommand.of("checkout", "-B", localName)
                    .revs(fetched).endOfRevisions().args());
            if (!reset.isOk()) return RefusalWords.failure(reset.stderr());
            repo.structure.maybeAutoUpdateSubmodules();
            return GitResult.ok("Reset " + localName + " to " + what + "; its previous commits are"
                    + " in the reflog (" + localName + "@{1})");
        }
        var switched = repo.runner.run(GitCommand.of("checkout").revs(localName).endOfRevisions().args());
        if (!switched.isOk()) return RefusalWords.failure(switched.stderr());
        if (fetched.equals(local)) {
            repo.structure.maybeAutoUpdateSubmodules();
            return GitResult.ok("Checked out " + what + " as " + localName);
        }
        if (behind) {
            var ff = repo.runner.run(GitCommand.of("merge", "--ff-only").revs(fetched).args());
            if (!ff.isOk()) {
                // An edit in the way of the fast-forward is the refusal JGit's engine gives
                // here too, so the checkout offers auto-stash rather than stopping half-way.
                return GitResult.refused("Checked out " + localName + ", but could not bring it up"
                        + " to date with " + what + ": " + ff.stderr(), RefusalWords.localChanges(ff.stderr()));
            }
            repo.structure.maybeAutoUpdateSubmodules();
            return GitResult.ok("Checked out " + what + " as " + localName + ", updated to its latest commit");
        }
        repo.structure.maybeAutoUpdateSubmodules();
        return GitResult.ok("Checked out " + localName + ". It already contains " + what
                + " and has local commits of its own, which were left as they are.");
    }

    public GitResult createBranch(String name, String startPoint, boolean checkout) {
        repo.ensureOpen();
        List<String> args;
        if (checkout) {
            // No `--` before the name: everything after one is a path. A trailing one is
            // what keeps the start point a revision rather than a pathspec.
            GitResult rejected = CliRepo.rejectIfOptionLike(name, "A branch name");
            if (rejected != null) return rejected;
            GitCommand create = GitCommand.of("checkout", "-b", name);
            if (startPoint != null && !startPoint.isBlank()) create.revs(startPoint);
            args = create.endOfRevisions().args();
        } else {
            // `branch` reads its separator as "names from here on", which keeps both the
            // new name and the start point from being read as options.
            args = new ArrayList<>(List.of("branch", "--", name));
            if (startPoint != null && !startPoint.isBlank()) args.add(startPoint);
        }
        var res = repo.runner.run(args);
        // With checkout, a switch refused for local changes is git's ordinary refusal.
        return res.isOk() ? GitResult.ok("Created branch " + name) : RefusalWords.failure(res.stderr());
    }

    public GitResult deleteBranch(String name, boolean force) {
        repo.ensureOpen();
        var res = repo.runner.run("branch", force ? "-D" : "-d", "--", name);
        if (res.isOk()) return GitResult.ok("Deleted branch " + name);
        return GitResult.refused(res.stderr(),
                RefusalWords.notFullyMerged(res.stderr()) ? new Refusal.NotFullyMerged(name) : null);
    }

    public GitResult renameBranch(String oldName, String newName) {
        repo.ensureOpen();
        var res = repo.runner.run("branch", "-m", "--", oldName, newName);
        return res.isOk() ? GitResult.ok("Renamed branch to " + newName) : GitResult.failed(res.stderr());
    }

    /** Whether {@code fullRef} names a ref that exists, exactly as written. */
    private boolean refExists(String fullRef) {
        return repo.runner.run("show-ref", "--verify", "--quiet", "--", fullRef).isOk();
    }

    /**
     * {@code %(upstream)} is worked out from the branch's configuration and the
     * remote's refspecs, so it still answers when the branch has gone from the remote -
     * where {@code rev-parse branch@{upstream}} fails.
     *
     * <p>The full name, shortened here: {@code %(upstream:short)} disambiguates the way
     * {@code %(refname:short)} does, so with a tag called {@code origin/main} as well the
     * upstream came back as {@code remotes/origin/main}, a name that matches no remote
     * branch in the sidebar.
     */
    public String getUpstream(String localBranch) {
        repo.ensureOpen();
        if (localBranch == null || localBranch.isBlank()) return null;
        var res = repo.runner.run("for-each-ref", "--format=%(upstream)", "--count=1",
                "refs/heads/" + localBranch.trim());
        String upstream = res.isOk() ? res.trimmedStdout() : "";
        return upstream.isEmpty() ? null : RefInfo.shortNameOf(upstream);
    }

    /**
     * Full ref names on both sides, cut down here: {@code :short} writes {@code heads/x}
     * for a branch that shares its name with a tag, and {@code remotes/o/x} for an
     * upstream that does.
     */
    public Map<String, String> getUpstreams() {
        repo.ensureOpen();
        var res = repo.runner.run("for-each-ref", "--format=%(refname)\t%(upstream)", "refs/heads");
        if (!res.isOk()) return Map.of();
        Map<String, String> upstreams = new java.util.LinkedHashMap<>();
        for (String line : res.stdout().split("\\r?\\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0 || !line.startsWith("refs/heads/")) continue;
            String upstream = line.substring(tab + 1).trim();
            if (!upstream.isEmpty()) {
                upstreams.put(line.substring("refs/heads/".length(), tab), RefInfo.shortNameOf(upstream));
            }
        }
        return upstreams;
    }

    /**
     * Both names are checked as refs before git sees them, which is also what keeps a
     * name starting with a dash from being read as an option: no such ref exists.
     */
    public GitResult setUpstream(String localBranch, String upstream) {
        repo.ensureOpen();
        String local = localBranch == null ? "" : localBranch.trim();
        String tracked = upstream == null ? "" : upstream.trim();
        if (local.isEmpty() || !refExists("refs/heads/" + local)) {
            return GitResult.failed("No such branch: " + local);
        }
        if (tracked.isEmpty() || !refExists("refs/remotes/" + tracked)) {
            return GitResult.failed("No such remote branch: " + tracked);
        }
        var res = repo.runner.run("branch", "--set-upstream-to=" + tracked, local);
        return res.isOk() ? GitResult.ok(local + " now tracks " + tracked) : GitResult.failed(res.stderr());
    }

    public GitResult unsetUpstream(String localBranch) {
        repo.ensureOpen();
        String local = localBranch == null ? "" : localBranch.trim();
        if (local.isEmpty() || !refExists("refs/heads/" + local)) {
            return GitResult.failed("No such branch: " + local);
        }
        var res = repo.runner.run("branch", "--unset-upstream", local);
        return res.isOk() ? GitResult.ok(local + " no longer tracks a remote branch")
                : GitResult.failed(res.stderr());
    }

    /**
     * Ahead/behind for every local branch, in one call.
     *
     * <p>{@code %(upstream:track)} rather than a {@code rev-list} per branch: a repository
     * with four hundred branches would otherwise mean four hundred subprocesses to draw a
     * sidebar. The field is empty for a branch with no upstream, and reads
     * {@code [ahead 2, behind 1]} otherwise - or {@code [gone]} when the upstream has been
     * deleted, which is not a count and is left out.
     *
     * <p>Keyed by the full name with {@code refs/heads/} cut off, not by
     * {@code %(refname:short)}: with a tag of the same name that wrote {@code heads/main},
     * a key the sidebar never asks for, and the branch lost its ahead/behind badge.
     */
    public Map<String, int[]> getBranchTracking() {
        repo.ensureOpen();
        var res = repo.runner.run("for-each-ref", "--format=%(refname)\t%(upstream:track)",
                "refs/heads");
        if (!res.isOk()) return Map.of();
        return CliParsers.parseBranchTracking(res.trimmedStdout());
    }

    /**
     * The same {@code %(upstream:track)} the counts come from, read for the one answer
     * {@link CliParsers#parseBranchTracking} leaves out: {@code [gone]}.
     */
    public java.util.Set<String> getGoneBranches() {
        repo.ensureOpen();
        var res = repo.runner.run("for-each-ref", "--format=%(refname)\t%(upstream:track)",
                "refs/heads");
        if (!res.isOk()) return java.util.Set.of();
        return CliParsers.parseGoneBranches(res.stdout());
    }

    /**
     * {@code --merged=} with the revision as the option's value, so a name starting with
     * a dash is never read as an option. A revision that does not resolve is an error to
     * git and an empty answer here.
     */
    public List<String> getBranchesMergedInto(String target) {
        repo.ensureOpen();
        if (target == null || target.isBlank()) return List.of();
        var res = repo.runner.run("for-each-ref", "--format=%(refname)", "--merged=" + target.trim(),
                "refs/heads");
        if (!res.isOk()) return List.of();
        List<String> merged = new ArrayList<>();
        for (String line : res.stdout().split("\\r?\\n")) {
            if (line.startsWith("refs/heads/")) merged.add(line.substring("refs/heads/".length()));
        }
        merged.sort(null);
        return merged;
    }

    public GitResult createTag(String name, String message, String commitish) {
        repo.ensureOpen();
        List<String> args = new ArrayList<>(List.of("tag"));
        boolean annotated = message != null && !message.isBlank();
        if (annotated) {
            // On stdin for the same reasons as a commit message - see commit().
            args.add("-a");
            args.add("-F");
            args.add("-");
        }
        args.add("--");
        args.add(name);
        if (commitish != null && !commitish.isBlank()) args.add(commitish);
        var res = annotated
                ? repo.runner.runWithInput(message.getBytes(StandardCharsets.UTF_8), args)
                : repo.runner.run(args);
        return res.isOk() ? GitResult.ok("Created tag " + name) : GitResult.failed(res.stderr());
    }

    public GitResult deleteTag(String name) {
        repo.ensureOpen();
        var res = repo.runner.run("tag", "-d", "--", name);
        return res.isOk() ? GitResult.ok("Deleted tag " + name) : GitResult.failed(res.stderr());
    }

    /** Reads the exact object stored in a tag ref, without peeling annotated tags. */
    public String getRawTagObjectId(String tagName) {
        repo.ensureOpen();
        String refName = tagRefName(tagName);
        if (!repo.runner.run("check-ref-format", refName).isOk()) {
            throw new IllegalArgumentException("Invalid tag name: " + tagName);
        }
        var result = repo.runner.run("show-ref", "--verify", "--hash", "--", refName);
        return result.isOk() ? result.trimmedStdout() : null;
    }

    /** Creates the tag ref only while absent, pointing at the original raw object. */
    public GitResult restoreTagRef(String tagName, String objectId) {
        repo.ensureOpen();
        String refName = tagRefName(tagName);
        if (!repo.runner.run("check-ref-format", refName).isOk()) {
            return GitResult.failed("Invalid tag name: " + tagName);
        }
        if (!isFullObjectId(objectId)) {
            return GitResult.failed("Invalid tag object ID");
        }
        if (!repo.runner.run("cat-file", "-e", objectId).isOk()) {
            return GitResult.failed("Unknown tag object: " + objectId);
        }
        byte[] command = ("create " + refName + " " + objectId + "\n")
                .getBytes(StandardCharsets.UTF_8);
        var result = repo.runner.runWithInput(command, List.of("update-ref", "--stdin"));
        return result.isOk()
                ? GitResult.ok("Restored tag " + tagName)
                : GitResult.failed(result.stderr().isBlank()
                        ? "Tag already exists or could not be restored: " + tagName
                        : result.stderr().trim());
    }

    private static String tagRefName(String tagName) {
        return "refs/tags/" + (tagName == null ? "" : tagName);
    }

    private static boolean isFullObjectId(String objectId) {
        return objectId != null && objectId.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})");
    }

    public boolean isTagMoveFastForward(String tagName, String newCommitish) {
        repo.ensureOpen();
        // `^{commit}` peels an annotated tag down to the commit it points at, the same
        // way JGit's own peeled-object-id already does for a lightweight or annotated
        // tag alike.
        var currentRes = repo.runner.run("rev-parse", "refs/tags/" + tagName + "^{commit}");
        if (!currentRes.isOk()) return false;
        var ancestry = repo.runner.run(GitCommand.of("merge-base", "--is-ancestor")
                .revs(currentRes.trimmedStdout(), newCommitish).args());
        return ancestry.isOk();
    }

    /**
     * Moves a tag by deleting and recreating it - {@code git tag -f} would drop an
     * annotated tag's message, so the pair stays.
     *
     * <p>The target is resolved <em>before</em> the delete, because the pair is not
     * atomic: a target that does not exist would otherwise leave the user with no tag at
     * all. If the recreate fails anyway, {@code update-ref} puts the old ref back on the
     * object it held, which restores an annotated tag exactly.
     */
    public GitResult moveTag(String tagName, String newCommitish) {
        repo.ensureOpen();
        if (tagName == null || tagName.isBlank()) return GitResult.failed("Tag name cannot be empty");
        if (newCommitish == null || newCommitish.isBlank()) {
            return GitResult.failed("No target commit for tag " + tagName);
        }
        var resolved = repo.runner.run("rev-parse", "--verify", "--quiet", newCommitish + "^{commit}");
        if (!resolved.isOk() || resolved.trimmedStdout().isEmpty()) {
            return GitResult.failed("Unknown commit: " + newCommitish);
        }
        var previous = repo.runner.run("rev-parse", "--verify", "--quiet", "refs/tags/" + tagName);
        String previousObject = previous.isOk() && !previous.trimmedStdout().isEmpty()
                ? previous.trimmedStdout() : null;

        TagMessage message = tagMessage(tagName);
        GitResult deleted = deleteTag(tagName);
        if (!deleted.isOk()) return deleted;
        GitResult created = createTag(tagName, message == null ? null : message.text(), newCommitish);
        if (!created.isOk()) {
            return GitResult.failed("Could not move tag " + tagName + ": " + created.getMessage()
                    + restoreTagSuffix(tagName, previousObject));
        }
        return GitResult.ok(message != null && message.wasSigned()
                ? "Moved tag " + tagName + " - its signature could not come with it;"
                  + " re-sign it with git tag -s -f if it needs one"
                : "Moved tag " + tagName);
    }

    /**
     * Puts {@code refs/tags/<name>} back on {@code objectId} after a failed move, and
     * reports the outcome as a sentence fragment to append to the failure message.
     */
    private String restoreTagSuffix(String tagName, String objectId) {
        if (objectId == null) return "";
        var res = repo.runner.run("update-ref", "refs/tags/" + tagName, objectId);
        if (res.isOk()) return " - the tag was left where it was";
        return " - and the tag could not be restored; it pointed at " + objectId;
    }

    /** An annotated tag's message without its signature, and whether it had one. */
    private record TagMessage(String text, boolean wasSigned) {
    }

    /**
     * The annotated tag's own message, or {@code null} for a lightweight tag.
     * {@code %(contents)} alone would silently fall back to the peeled commit's own
     * message for a lightweight tag, so {@code %(objecttype)} is checked first.
     *
     * <p>{@code %(contents)} of a signed tag ends with its PGP or SSH signature block.
     * Carried into the recreated tag, that block became plain message text - a
     * signature-shaped paragraph that verifies nothing - so it is cut off using
     * {@code %(contents:signature)}, which is exactly that block.
     */
    private TagMessage tagMessage(String tagName) {
        var res = repo.runner.run("for-each-ref",
                "--format=%(objecttype)%00%(contents:signature)%00%(contents)", "refs/tags/" + tagName);
        if (!res.isOk()) return null;
        String[] fields = res.stdout().split("\0", 3);
        if (fields.length < 3 || !"tag".equals(fields[0].trim())) return null;
        String signature = fields[1];
        String contents = fields[2];
        boolean signed = !signature.isBlank();
        if (signed) {
            int at = contents.lastIndexOf(signature);
            if (at >= 0) contents = contents.substring(0, at);
        }
        return new TagMessage(contents.strip(), signed);
    }
}
