package com.jfx.git.core;

import com.jfx.git.model.PullMode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Remotes, fetch, pull and push on the native engine.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliRemotes {

    private final CliRepo repo;

    CliRemotes(CliRepo repo) {
        this.repo = repo;
    }

    public GitResult fetchRemoteRef(String remoteNameOrUrl, String remoteRef) {
        repo.ensureOpen();
        // "--": the remote may be a raw clone URL from a provider's API, and one beginning
        // with "-" must reach git as a repository, not as an option. The same holds in
        // checkoutRemoteRef and checkoutPullRequest.
        var res = repo.runNetwork(List.of("fetch", "--", remoteNameOrUrl,
                "+" + remoteRef + ":" + GitBackend.INTERNAL_REF_PREFIX + "review/fetched"));
        return res.isOk() ? GitResult.ok("Fetched " + remoteRef) : GitResult.failed(res.stderr());
    }

    public List<String> getRemoteNames() {
        repo.ensureOpen();
        var res = repo.runner.run("remote");
        if (!res.isOk()) return List.of();
        List<String> names = new ArrayList<>();
        for (String s : res.trimmedStdout().split("\\r?\\n")) {
            if (!s.isBlank()) names.add(s.trim());
        }
        return names;
    }

    public String getRemoteUrl(String remoteName) {
        repo.ensureOpen();
        var res = repo.runner.run("remote", "get-url", "--", remoteName);
        return res.isOk() ? res.trimmedStdout() : null;
    }

    /**
     * {@code --} before the name and URL: both are user input, and without it a URL
     * pasted as {@code --upload-pack=...} or a name starting with a dash is read as an
     * option. The same separator guards every remote and submodule command below.
     */
    public GitResult addRemote(String name, String uri) {
        repo.ensureOpen();
        var res = repo.runner.run("remote", "add", "--", name, uri);
        return res.isOk() ? GitResult.ok("Added remote " + name) : GitResult.failed(res.stderr());
    }

    public GitResult removeRemote(String name) {
        repo.ensureOpen();
        var res = repo.runner.run("remote", "remove", "--", name);
        return res.isOk() ? GitResult.ok("Removed remote " + name) : GitResult.failed(res.stderr());
    }

    public String getRemotePushUrl(String remoteName) {
        repo.ensureOpen();
        if (remoteName == null || remoteName.isBlank()) return null;
        var res = repo.runner.run("config", "--get", "remote." + remoteName + ".pushurl");
        String url = res.isOk() ? res.trimmedStdout() : "";
        return url.isEmpty() ? null : url;
    }

    public GitResult renameRemote(String oldName, String newName) {
        repo.ensureOpen();
        String from = oldName == null ? "" : oldName.trim();
        String to = newName == null ? "" : newName.trim();
        if (from.isEmpty() || to.isEmpty()) return GitResult.failed("A remote needs a name");
        List<String> remotes = getRemoteNames();
        if (!remotes.contains(from)) return GitResult.failed("No such remote: " + from);
        if (remotes.contains(to)) return GitResult.failed("Remote " + to + " already exists");
        var res = repo.runner.run("remote", "rename", "--", from, to);
        return res.isOk() ? GitResult.ok("Renamed remote " + from + " to " + to)
                : GitResult.failed(res.stderr());
    }

    public GitResult setRemoteUrl(String name, String url, boolean push) {
        repo.ensureOpen();
        String remote = name == null ? "" : name.trim();
        String target = url == null ? "" : url.trim();
        if (!getRemoteNames().contains(remote)) return GitResult.failed("No such remote: " + remote);
        if (target.isEmpty() && !push) {
            return GitResult.failed("A remote needs a URL to fetch from");
        }
        if (target.isEmpty()) {
            // Nothing to unset is the state asked for, already; git calls it an error.
            if (getRemotePushUrl(remote) != null) {
                var res = repo.runner.run("config", "--unset-all", "remote." + remote + ".pushurl");
                if (!res.isOk()) return GitResult.failed(res.stderr());
            }
            return GitResult.ok("Pushes to " + remote + " now go to its fetch URL");
        }
        var res = push
                ? repo.runner.run("remote", "set-url", "--push", "--", remote, target)
                : repo.runner.run("remote", "set-url", "--", remote, target);
        if (!res.isOk()) return GitResult.failed(res.stderr());
        return GitResult.ok(push ? "Changed where " + remote + " is pushed to"
                : "Changed the URL of " + remote);
    }

    public GitResult fetchRemote(String name) {
        repo.ensureOpen();
        String remote = name == null ? "" : name.trim();
        if (!getRemoteNames().contains(remote)) return GitResult.failed("No such remote: " + remote);
        return CliParsers.classifyFetch(repo.runNetwork(List.of("fetch", "--prune", "--", remote)), remote);
    }

    /**
     * The remote is checked against the configured names first, so it cannot be read as
     * an option; the branch travels inside a refspec, which cannot be one either.
     */
    public GitResult deleteRemoteBranch(String remoteName, String branchName) {
        repo.ensureOpen();
        String remote = remoteName == null ? "" : remoteName.trim();
        String branch = branchName == null ? "" : branchName.trim();
        if (branch.isEmpty()) return GitResult.failed("Branch name cannot be empty");
        if (!getRemoteNames().contains(remote)) return GitResult.failed("No such remote: " + remote);
        var res = repo.runNetwork(List.of("push", remote, ":refs/heads/" + branch));
        if (!res.isOk()) return GitResult.failed(res.stderr());
        // The remote's own words, relayed untranslated: it accepts the deletion of a
        // branch it does not have, with a warning, and git exits 0.
        if (res.stderr().contains("deleting a non-existent ref")) {
            repo.runner.run("update-ref", "-d", "refs/remotes/" + remote + "/" + branch);
            return GitResult.failed(RemoteOps.alreadyGoneExplanation(remote, branch));
        }
        return GitResult.ok("Deleted " + branch + " from " + remote);
    }

    /**
     * Fetches every remote, pruning deleted branches, with git's own tag auto-following.
     *
     * <p>Not {@code --tags}. That asks for every tag the remote has, and a single local
     * tag that differs from the remote's - a re-tagged release, a tag made locally
     * before one of the same name was pushed - is then rejected as "would clobber
     * existing tag", git exits 1, and every fetch from then on, auto-fetch included,
     * fails for good. Auto-following fetches the tags that point into what was fetched,
     * which is what JGit does, so the two engines now agree. When a clobber does happen
     * anyway (a remote configured with {@code tagOpt = --tags}), the branches still
     * arrived, and the result says which tags did not rather than calling it a failure.
     */
    public GitResult fetchAll() {
        repo.ensureOpen();
        return CliParsers.classifyFetch(repo.runNetwork(List.of("fetch", "--all", "--prune")));
    }

    /**
     * Fetches with every prompt switched off - see {@link AskpassBridge#silentEnvironment}.
     *
     * <p>The earlier comment here claimed {@code GIT_TERMINAL_PROMPT=0} already made this
     * the same as {@link #fetchAll()}. It did not: that variable covers git's own HTTPS
     * prompt only. ssh still asked about unknown host keys and key passphrases - on the
     * terminal the application was started from, if any, where the single git worker
     * waited for an answer nobody could see - and Git Credential Manager could still put
     * up its sign-in window from a timer. Now none of them can.
     */
    public GitResult fetchAllSilently() {
        repo.ensureOpen();
        return CliParsers.classifyFetch(repo.runSilentNetwork(List.of("fetch", "--all", "--prune")));
    }

    /**
     * Pulls in exactly the requested mode.
     *
     * <p>Merge was a bare {@code git pull}, which leaves the choice to configuration: with
     * nothing configured, modern git refuses a diverged branch outright ("Need to specify
     * how to reconcile divergent branches"), and with {@code pull.rebase=true} "Merge"
     * silently rebased. Each mode now says what it means. Merge adds {@code --ff} only
     * when neither {@code pull.ff} nor {@code merge.ff} is set, so a repository that
     * configured one keeps it.
     */
    public GitResult pull(PullMode mode) {
        repo.ensureOpen();
        List<String> args = new ArrayList<>(List.of("pull"));
        switch (mode) {
            case REBASE -> args.add("--rebase");
            case FF_ONLY -> {
                args.add("--no-rebase");
                args.add("--ff-only");
            }
            case MERGE -> {
                args.add("--no-rebase");
                // One read of the configuration rather than a process per key.
                Map<String, String> config = repo.configValues();
                boolean ffConfigured = !config.getOrDefault("pull.ff", "").isBlank()
                        || !config.getOrDefault("merge.ff", "").isBlank();
                if (!ffConfigured) args.add("--ff");
            }
        }
        var res = repo.runNetwork(args);
        if (!res.isOk()) return repo.integration.conflictOrFailed("Pull stopped with conflicts", res);
        repo.structure.maybeAutoUpdateSubmodules();
        return GitResult.ok("Pulled updates");
    }

    /**
     * Where a push of {@code branch} goes; see {@link PushTarget}. git prints section and
     * variable names in lower case and keeps the subsection (the branch name) as written.
     */
    private PushTarget pushTarget(String branch, String remote) {
        Map<String, String> config = repo.configValues();
        return PushTarget.resolve(branch, remote, getRemoteNames(), (section, subsection, name) -> {
            String key = section.toLowerCase(java.util.Locale.ROOT)
                    + (subsection == null ? "" : "." + subsection)
                    + "." + name.toLowerCase(java.util.Locale.ROOT);
            return config.get(key);
        });
    }

    /**
     * The commit the push target is known here to have for the current branch.
     *
     * <p>Read from the remote-tracking ref rather than delegated to bare
     * {@code --force-with-lease}: the bare form takes its expectation from the same ref,
     * but says nothing when there is no such ref, and the caller needs to know the
     * difference between "unpublished branch" and "published, never fetched".
     */
    public PushLease pushLease(String remote) {
        repo.ensureOpen();
        if (repo.branches.isHeadDetached()) return null;
        String branch = repo.branches.getCurrentBranch();
        if (branch == null || branch.isBlank()) return null;
        PushTarget target = pushTarget(branch, remote);

        var tracked = repo.runner.run("rev-parse", "--verify", "--quiet", target.trackingRef());
        if (tracked.isOk() && !tracked.stdout().isBlank()) {
            return withIntegration(PushLease.at(target.remote(), branch, target.remoteBranch(),
                    tracked.stdout().trim()));
        }
        // Nothing tracked. Only the remote can say whether that is because the branch was
        // never published, or because this clone has never fetched it - and forcing over
        // commits that were never fetched is exactly what the lease exists to stop.
        var advertised = repo.runNetwork(List.of("ls-remote", "--heads", "--", target.remote(), target.remoteRef()));
        if (!advertised.isOk()) return null;
        return advertised.stdout().isBlank()
                ? PushLease.absent(target.remote(), branch, target.remoteBranch())
                : null;
    }

    /**
     * Whether the leased commit was ever part of the local branch - reachable from its
     * tip or from anything its reflog records - and what forcing would discard.
     *
     * <p>git's {@code --force-if-includes} performs this check, but only when the lease
     * is taken from the tracking ref at push time; with an explicit
     * {@code --force-with-lease=<ref>:<expect>}, which this engine must use so the commit
     * the user confirmed is the one checked, git documents the flag as a no-op. So the
     * check is made here, the same way on both engines, before the push is attempted.
     */
    private PushLease withIntegration(PushLease lease) {
        if (lease.expectsNoRemoteBranch()) return lease;
        String expected = lease.expectedObjectId();
        String localRef = "refs/heads/" + lease.branch();
        boolean integrated = repo.runner.run(GitCommand.of("merge-base", "--is-ancestor")
                .revs(expected, localRef).args()).isOk();
        if (!integrated) {
            var reflog = repo.runner.run("reflog", "show", "--format=%H", localRef, "--");
            if (reflog.isOk() && !reflog.trimmedStdout().isEmpty()) {
                // rev-list prints the leased commit unless one of the reflog entries
                // reaches it. Fed on stdin: a long reflog would overflow a command line.
                StringBuilder input = new StringBuilder(expected).append('\n');
                reflog.trimmedStdout().lines().map(String::trim).filter(s -> !s.isEmpty())
                        .distinct().forEach(hash -> input.append('^').append(hash).append('\n'));
                var unreached = repo.runner.runWithInput(input.toString().getBytes(StandardCharsets.UTF_8),
                        List.of("rev-list", "--max-count=1", "--stdin"));
                integrated = unreached.isOk() && unreached.trimmedStdout().isEmpty();
            }
        }
        var discarded = repo.runner.run(GitCommand.of("log", "--format=%h %s",
                        "--max-count=" + (PushLease.DISCARD_LIST_LIMIT + 1))
                .revs(expected, "^" + localRef).endOfRevisions().args());
        List<String> lines = new ArrayList<>();
        if (discarded.isOk()) {
            discarded.trimmedStdout().lines().filter(s -> !s.isBlank()).forEach(lines::add);
        }
        if (lines.size() > PushLease.DISCARD_LIST_LIMIT) {
            lines = new ArrayList<>(lines.subList(0, PushLease.DISCARD_LIST_LIMIT));
            lines.add("…");
        }
        return lease.withIntegration(integrated, lines);
    }

    public GitResult push(String remote, PushLease lease, boolean includeTags, boolean setUpstream) {
        repo.ensureOpen();
        // Detached HEAD: with no refspec, git falls back to push.default and fails with
        // something about "does not match any". Say what is actually wrong instead.
        if (repo.branches.isHeadDetached()) {
            return GitResult.failed("HEAD is detached, so there is no branch to push. "
                    + "Create a branch here first, then push it.");
        }
        String branch = repo.branches.getCurrentBranch();
        if (branch == null || branch.isBlank()) {
            return GitResult.failed("There is no branch to push.");
        }
        PushTarget target = pushTarget(branch, remote == null && lease != null ? lease.remote() : remote);
        if (lease != null) {
            if (!branch.equals(lease.branch())) {
                return GitResult.failed("Force push refused: the lease was taken on "
                        + lease.branch() + ", but " + branch + " is checked out now.");
            }
            if (!target.remote().equals(lease.remote())
                    || !target.remoteBranch().equals(lease.remoteBranch())) {
                return GitResult.failed("Force push refused: the lease was taken on "
                        + lease.remote() + "/" + lease.remoteBranch() + ", but this push"
                        + " would go to " + target.remote() + "/" + target.remoteBranch() + ".");
            }
            String notIntegrated = lease.refusalIfNotIntegrated();
            if (notIntegrated != null) return GitResult.failed(notIntegrated);
        }

        List<String> args = new ArrayList<>(List.of("push"));
        // -u only when this push publishes the branch, or the caller explicitly asks:
        // on every push it re-pointed a configured upstream at <remote>/<same name>.
        if (setUpstream || target.publish()) args.add("-u");
        if (lease != null && !lease.expectsNoRemoteBranch()) {
            // The expectation is spelled out rather than left to the bare flag, so the
            // commit the user confirmed overwriting is the one git checks for.
            args.add("--force-with-lease=" + lease.ref() + ":" + lease.expectedObjectId());
        }
        // --follow-tags, not --tags: annotated tags pointing into what is pushed and
        // missing from the remote. --tags sent every tag, and one stale local tag made
        // the whole push "rejected".
        if (includeTags) args.add("--follow-tags");
        // A remote (or a pushRemote URL) beginning with "-" must not be read as an option.
        args.add("--");
        args.add(target.remote());
        // The refspec is not optional. Without it git falls back to push.default, which
        // under the default "simple" has no upstream to work from on an unpublished
        // branch and fails instead of publishing it - even with -u, which only records an
        // upstream after a successful push rather than choosing what to send.
        args.add("refs/heads/" + branch + ":" + target.remoteRef());

        var res = repo.runNetwork(args);
        String where = target.remote()
                + (target.remoteBranch().equals(branch) ? "" : "/" + target.remoteBranch());
        return res.isOk()
                ? GitResult.ok("Pushed " + branch + " to " + where)
                : GitResult.failed(res.stderr());
    }

    public GitResult deleteRemoteTag(String remoteName, String tagName) {
        repo.ensureOpen();
        if (tagName == null || tagName.isBlank()) return GitResult.failed("Tag name cannot be empty");
        String target = (remoteName == null || remoteName.isBlank()) ? "origin" : remoteName;
        // "--" before the remote, as push() has: a remote (or URL) beginning with a dash
        // was otherwise read as an option. push takes no paths, so nothing after it is
        // misread either.
        var res = repo.runNetwork(List.of("push", "--", target, ":refs/tags/" + tagName));
        return res.isOk() ? GitResult.ok("Deleted " + tagName + " from " + target) : GitResult.failed(res.stderr());
    }

    public GitResult pushTag(String remoteName, String tagName) {
        repo.ensureOpen();
        if (tagName == null || tagName.isBlank()) return GitResult.failed("Tag name cannot be empty");
        String target = (remoteName == null || remoteName.isBlank()) ? "origin" : remoteName;
        var res = repo.runNetwork(List.of("push", "--", target, "refs/tags/" + tagName));
        return res.isOk() ? GitResult.ok("Pushed " + tagName + " to " + target) : GitResult.failed(res.stderr());
    }
}
