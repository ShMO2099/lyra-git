package com.jfx.git.core;

import com.jfx.git.model.PullMode;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Remotes, fetch, pull, push with its lease, and checking out pull requests and other
 * fetched refs, for {@link GitService}.
 */
final class JGitRemotes extends JGitPart {

    JGitRemotes(JGitRepo ctx) {
        super(ctx);
    }

    public GitResult checkoutPullRequest(String remoteName, int number, boolean resetToFetched) {
        if (git() == null) return GitResult.failed("No repository open");
        String localName = "pr-" + number;
        // Not refs/remotes/<remote>/pr/<n>: no fetch refspec covers it, so it would sit
        // in the sidebar's remote-branch tree and decorate the graph forever.
        String remoteTrackingRef = GitBackend.INTERNAL_REF_PREFIX + "pr/" + remoteName + "/" + number;
        try {
            ctx.overNetwork(git().fetch()
                    .setRemote(remoteName)
                    .setRefSpecs(new RefSpec("+refs/pull/" + number + "/head:" + remoteTrackingRef)));
            return checkoutFetched(localName, remoteTrackingRef, "pull request #" + number, resetToFetched);
        } catch (Exception e) {
            return failure("Checkout failed: ", e);
        }
    }

    public GitResult checkoutRemoteRef(String remoteNameOrUrl, String remoteRef, String localBranchName,
                                       boolean resetToFetched) {
        if (git() == null) return GitResult.failed("No repository open");
        String trackingRef = GitBackend.INTERNAL_REF_PREFIX + "checkout/" + localBranchName;
        try {
            ctx.overNetwork(git().fetch()
                    .setRemote(remoteNameOrUrl)
                    .setRefSpecs(new RefSpec("+" + remoteRef + ":" + trackingRef)));
            return checkoutFetched(localBranchName, trackingRef, remoteRef, resetToFetched);
        } catch (Exception e) {
            return failure("Checkout failed: ", e);
        }
    }

    /**
     * Checks out {@code localName} at the commit just fetched into {@code fetchedRef},
     * without ever throwing away commits the local branch has.
     *
     * <p>The first checkout creates the branch. A second one - the pull request moved on,
     * or the user is simply coming back to it - used to differ by engine: the CLI ran
     * {@code checkout -B}, silently resetting the branch and discarding any fix-up
     * commits made on it, while JGit passed a start point without asking to create the
     * branch, which JGit ignores, so it switched to the stale branch and still reported
     * "Checked out pull request #N". Both now do what a person would: fast-forward when
     * the branch is simply behind, leave it when it is ahead, and when the two have
     * diverged refuse with a {@link DivergedCheckout} the dialogs turn into a question -
     * or, once the user has answered it, move the branch onto the fetched head.
     */
    private GitResult checkoutFetched(String localName, String fetchedRef, String what,
                                      boolean resetToFetched) throws Exception {
        Ref existing = repository().findRef(Constants.R_HEADS + localName);
        if (existing == null) {
            git().checkout().setName(localName).setCreateBranch(true).setStartPoint(fetchedRef).call();
            structure().maybeAutoUpdateSubmodules();
            return GitResult.ok("Checked out " + what + " as " + localName);
        }
        ObjectId fetched = repository().resolve(fetchedRef);
        ObjectId local = existing.getObjectId();
        if (fetched == null) return GitResult.failed("Checkout failed: nothing was fetched for " + what);
        if (fetched.equals(local)) {
            git().checkout().setName(localName).call();
            structure().maybeAutoUpdateSubmodules();
            return GitResult.ok("Checked out " + what + " as " + localName);
        }
        if (reachable(local, fetched)) {
            git().checkout().setName(localName).call();
            MergeResult ff = git().merge().include(fetched)
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call();
            if (!ff.getMergeStatus().isSuccessful()) {
                return GitResult.failed("Checked out " + localName + ", but could not bring it up"
                        + " to date with " + what + ": " + ff.getMergeStatus());
            }
            structure().maybeAutoUpdateSubmodules();
            return GitResult.ok("Checked out " + what + " as " + localName + ", updated to its latest commit");
        }
        if (reachable(fetched, local)) {
            git().checkout().setName(localName).call();
            structure().maybeAutoUpdateSubmodules();
            return GitResult.ok("Checked out " + localName + ". It already contains " + what
                    + " and has local commits of its own, which were left as they are.");
        }
        if (!resetToFetched) {
            return DivergedCheckout.refusal(localName, what, countOnlyIn(local, fetched));
        }
        // Detach onto the fetched commit first. That is an ordinary checkout, so local
        // changes are carried across or it refuses - never the hard reset that moving a
        // checked-out branch with a plain ref update and a forced checkout would be. Only
        // once the tree is there is the branch moved, and HEAD put back on it.
        git().checkout().setName(fetched.getName()).call();
        RefUpdate update = repository().updateRef(Constants.R_HEADS + localName);
        update.setNewObjectId(fetched);
        update.setExpectedOldObjectId(local);
        update.setForceUpdate(true);
        // The reflog entry is what keeps the discarded commits findable.
        update.setRefLogMessage("reset: moving to " + what, false);
        RefUpdate.Result moved = update.update();
        if (moved != RefUpdate.Result.FORCED && moved != RefUpdate.Result.FAST_FORWARD) {
            git().checkout().setName(localName).call();
            return GitResult.failed("Could not reset " + localName + " to " + what + ": " + moved);
        }
        git().checkout().setName(localName).call();
        structure().maybeAutoUpdateSubmodules();
        return GitResult.ok("Reset " + localName + " to " + what + "; its previous commits are"
                + " in the reflog (" + localName + "@{1})");
    }

    /** How many commits {@code tip} has that {@code exclude} does not: {@code rev-list --count}. */
    private int countOnlyIn(ObjectId tip, ObjectId exclude) throws IOException {
        int count = 0;
        try (RevWalk walk = new RevWalk(repository())) {
            walk.markStart(walk.parseCommit(tip));
            walk.markUninteresting(walk.parseCommit(exclude));
            for (RevCommit _ : walk) count++;
        }
        return count;
    }

    public GitResult fetchRemoteRef(String remoteNameOrUrl, String remoteRef) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            ctx.overNetwork(git().fetch()
                    .setRemote(remoteNameOrUrl)
                    .setRefSpecs(new RefSpec("+" + remoteRef + ":" + GitBackend.INTERNAL_REF_PREFIX + "review/fetched")));
            return GitResult.ok("Fetched " + remoteRef);
        } catch (Exception e) {
            return GitResult.failed("Fetch failed: " + describe(e));
        }
    }

    // ------------------------------------------------------------------
    // Remote operations
    // ------------------------------------------------------------------

    public List<String> getRemoteNames() {
        if (git() == null) return List.of();
        try {
            List<String> names = new ArrayList<>();
            for (RemoteConfig rc : git().remoteList().call()) {
                names.add(rc.getName());
            }
            return names;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list remotes", e);
            return List.of();
        }
    }

    public String getRemoteUrl(String remoteName) {
        if (git() == null) return null;
        try {
            for (RemoteConfig rc : git().remoteList().call()) {
                if (rc.getName().equals(remoteName) && !rc.getURIs().isEmpty()) {
                    return rc.getURIs().get(0).toString();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read remote URL for " + remoteName, e);
        }
        return null;
    }

    public GitResult addRemote(String name, String uri) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            git().remoteAdd().setName(name).setUri(new URIish(uri)).call();
            return GitResult.ok("Added remote " + name);
        } catch (Exception e) {
            return GitResult.failed("Could not add remote: " + describe(e));
        }
    }

    public GitResult removeRemote(String name) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            git().remoteRemove().setRemoteName(name).call();
            return GitResult.ok("Removed remote " + name);
        } catch (Exception e) {
            return GitResult.failed("Could not remove remote: " + describe(e));
        }
    }

    public String getRemotePushUrl(String remoteName) {
        if (repository() == null || remoteName == null || remoteName.isBlank()) return null;
        String url = repository().getConfig().getString("remote", remoteName, "pushurl");
        return url == null || url.isBlank() ? null : url;
    }

    /**
     * Renames a remote by hand: JGit has no command for it.
     *
     * <p>In git's own order - configuration first, then the refs. The section is copied
     * key by key so anything else set on the remote ({@code tagOpt}, {@code prune},
     * {@code pushurl}) comes along, and its fetch refspecs are re-pointed at the new
     * {@code refs/remotes/} directory. Then every branch that named the remote as its
     * upstream or push remote, and {@code remote.pushDefault}. Then the remote-tracking
     * refs: symbolic ones ({@code origin/HEAD}) are taken down first and put back last,
     * pointing at the renamed branch, because renaming one in place would rename what it
     * points at.
     */
    public GitResult renameRemote(String oldName, String newName) {
        if (git() == null) return GitResult.failed("No repository open");
        String from = oldName == null ? "" : oldName.trim();
        String to = newName == null ? "" : newName.trim();
        if (from.isEmpty() || to.isEmpty()) return GitResult.failed("A remote needs a name");
        List<String> remotes = getRemoteNames();
        if (!remotes.contains(from)) return GitResult.failed("No such remote: " + from);
        if (remotes.contains(to)) return GitResult.failed("Remote " + to + " already exists");
        if (to.startsWith("-") || !Repository.isValidRefName(Constants.R_REMOTES + to + "/x")) {
            return GitResult.failed("'" + to + "' is not a valid remote name");
        }
        String oldPrefix = Constants.R_REMOTES + from + "/";
        String newPrefix = Constants.R_REMOTES + to + "/";
        try {
            var config = repository().getConfig();
            for (String key : config.getNames("remote", from)) {
                List<String> values = new ArrayList<>();
                for (String value : config.getStringList("remote", from, key)) {
                    values.add("fetch".equalsIgnoreCase(key)
                            ? value.replace(":" + oldPrefix, ":" + newPrefix)
                            : value);
                }
                config.setStringList("remote", to, key, values);
            }
            config.unsetSection("remote", from);
            for (String branch : config.getSubsections(org.eclipse.jgit.lib.ConfigConstants.CONFIG_BRANCH_SECTION)) {
                for (String key : List.of("remote", "pushRemote")) {
                    if (from.equals(config.getString("branch", branch, key))) {
                        config.setString("branch", branch, key, to);
                    }
                }
            }
            if (from.equals(config.getString("remote", null, "pushDefault"))) {
                config.setString("remote", null, "pushDefault", to);
            }
            config.save();

            Map<String, String> symbolic = new java.util.LinkedHashMap<>();
            List<String> plain = new ArrayList<>();
            for (Ref ref : repository().getRefDatabase().getRefsByPrefix(oldPrefix)) {
                if (ref.isSymbolic()) {
                    symbolic.put(ref.getName(), ref.getTarget().getName());
                } else {
                    plain.add(ref.getName());
                }
            }
            List<String> stranded = new ArrayList<>();
            for (String name : symbolic.keySet()) {
                // Detached: the link itself, not the branch it points at.
                RefUpdate unlink = repository().getRefDatabase().newUpdate(name, true);
                unlink.setForceUpdate(true);
                unlink.delete();
            }
            for (String name : plain) {
                String moved = newPrefix + name.substring(oldPrefix.length());
                RefUpdate.Result outcome = repository().renameRef(name, moved).rename();
                if (outcome != RefUpdate.Result.RENAMED) {
                    stranded.add(Repository.shortenRefName(name) + " (" + outcome + ")");
                }
            }
            for (Map.Entry<String, String> link : symbolic.entrySet()) {
                String moved = newPrefix + link.getKey().substring(oldPrefix.length());
                String target = link.getValue().startsWith(oldPrefix)
                        ? newPrefix + link.getValue().substring(oldPrefix.length())
                        : link.getValue();
                repository().getRefDatabase().newUpdate(moved, true).link(target);
            }
            if (!stranded.isEmpty()) {
                return GitResult.failed("Renamed remote " + from + " to " + to
                        + ", but could not move " + String.join(", ", stranded)
                        + ". Fetch " + to + " to get them back.");
            }
            return GitResult.ok("Renamed remote " + from + " to " + to);
        } catch (Exception e) {
            return GitResult.failed("Could not rename remote: " + describe(e));
        }
    }

    /**
     * Written to the configuration directly rather than through JGit's
     * {@code RemoteSetUrlCommand}, which creates the remote when it does not exist - a
     * section with a URL and no fetch refspec, which then fetches nothing.
     */
    public GitResult setRemoteUrl(String name, String url, boolean push) {
        if (git() == null) return GitResult.failed("No repository open");
        String remote = name == null ? "" : name.trim();
        String target = url == null ? "" : url.trim();
        if (!getRemoteNames().contains(remote)) return GitResult.failed("No such remote: " + remote);
        if (target.isEmpty() && !push) {
            return GitResult.failed("A remote needs a URL to fetch from");
        }
        try {
            var config = repository().getConfig();
            if (target.isEmpty()) {
                config.unset("remote", remote, "pushurl");
                config.save();
                return GitResult.ok("Pushes to " + remote + " now go to its fetch URL");
            }
            new URIish(target);   // the same check addRemote makes
            config.setString("remote", remote, push ? "pushurl" : "url", target);
            config.save();
            return GitResult.ok(push ? "Changed where " + remote + " is pushed to"
                    : "Changed the URL of " + remote);
        } catch (Exception e) {
            return GitResult.failed("Could not change the URL: " + describe(e));
        }
    }

    public GitResult fetchRemote(String name) {
        if (git() == null) return GitResult.failed("No repository open");
        String remote = name == null ? "" : name.trim();
        if (!getRemoteNames().contains(remote)) return GitResult.failed("No such remote: " + remote);
        try {
            ctx.overNetwork(git().fetch().setRemote(remote).setRemoveDeletedRefs(true));
            return GitResult.ok("Fetched " + remote);
        } catch (Exception e) {
            return GitResult.failed("Could not fetch " + remote + ": " + describe(e));
        }
    }

    public GitResult deleteRemoteBranch(String remoteName, String branchName) {
        if (git() == null) return GitResult.failed("No repository open");
        String remote = remoteName == null ? "" : remoteName.trim();
        String branch = branchName == null ? "" : branchName.trim();
        if (branch.isEmpty()) return GitResult.failed("Branch name cannot be empty");
        if (!getRemoteNames().contains(remote)) return GitResult.failed("No such remote: " + remote);
        // Deleting a branch is a push, and pre-push hooks see it like any other.
        String unrunnable = prePushHookThisEngineCannotRun();
        if (unrunnable != null) return GitResult.failed(unrunnable);
        try {
            List<RemoteRefUpdate> rejected = notLanded(ctx.overNetwork(git().push()
                    .setRemote(remote)
                    .setRefSpecs(new RefSpec(":" + Constants.R_HEADS + branch))));
            boolean alreadyGone = rejected.removeIf(
                    update -> update.getStatus() == RemoteRefUpdate.Status.NON_EXISTING);
            if (!rejected.isEmpty()) {
                return GitResult.failed("Could not delete " + branch + " on " + remote
                        + " - " + describeRejections(rejected));
            }
            // The push takes the tracking ref with it where the remote's fetch refspec
            // covers the branch; this is for the remote whose refspec does not, and for
            // the branch that had already gone, whose stale record is all there was.
            String tracking = Constants.R_REMOTES + remote + "/" + branch;
            if (repository().exactRef(tracking) != null) {
                RefUpdate forget = repository().updateRef(tracking);
                forget.setForceUpdate(true);
                forget.delete();
            }
            if (alreadyGone) {
                return GitResult.failed(RemoteOps.alreadyGoneExplanation(remote, branch));
            }
            return GitResult.ok("Deleted " + branch + " from " + remote);
        } catch (Exception e) {
            return GitResult.failed("Could not delete remote branch: " + describe(e));
        }
    }

    /**
     * Fetches every configured remote, pruning deleted branches.
     *
     * <p>One remote at a time, and one failure does not stop the others: a repository
     * with a dead {@code upstream} beside a healthy {@code origin} used to fetch neither,
     * because the first exception ended the loop. The failures are collected and
     * reported together, and the remotes that did fetch stay fetched.
     */
    public GitResult fetchAll() {
        if (git() == null) return GitResult.failed("No repository open");
        List<String> remotes = getRemoteNames();
        if (remotes.isEmpty()) {
            return GitResult.failed("No remotes configured");
        }
        List<String> fetched = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String remote : remotes) {
            if (GitTaskRunner.isCancelled()) {
                failures.add("cancelled before " + remote);
                break;
            }
            try {
                ctx.overNetwork(git().fetch().setRemote(remote).setRemoveDeletedRefs(true));
                fetched.add(remote);
            } catch (Exception e) {
                failures.add(remote + ": " + describe(e));
            }
        }
        if (failures.isEmpty()) {
            return GitResult.ok("Fetched " + String.join(", ", fetched));
        }
        if (fetched.isEmpty()) {
            return GitResult.failed("Fetch failed: " + String.join("; ", failures));
        }
        return GitResult.failed("Fetched " + String.join(", ", fetched)
                + ", but could not fetch " + String.join("; ", failures));
    }

    /**
     * Every ref update a push did not land: anything but {@code OK} or already up to date.
     * Mutable, so a caller can take out the statuses it reads differently.
     */
    private static List<RemoteRefUpdate> notLanded(Iterable<PushResult> results) {
        List<RemoteRefUpdate> rejected = new ArrayList<>();
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                    rejected.add(update);
                }
            }
        }
        return rejected;
    }

    /** {@code refs/heads/x: REJECTED_NONFASTFORWARD - message; ...}, for a failure message. */
    private static String describeRejections(List<RemoteRefUpdate> rejected) {
        List<String> lines = new ArrayList<>();
        for (RemoteRefUpdate update : rejected) {
            lines.add(update.getRemoteName() + ": " + update.getStatus()
                    + (update.getMessage() == null ? "" : " - " + update.getMessage()));
        }
        return String.join("; ", lines);
    }

    public GitResult fetchAllSilently() {
        GitResult[] outcome = new GitResult[1];
        CredentialsManager.runSilently(() -> outcome[0] = fetchAll());
        return outcome[0];
    }

    /**
     * Pulls in the requested mode, whatever the configuration would have chosen.
     *
     * <p>JGit's {@code PullCommand} reads {@code pull.rebase} and {@code branch.B.rebase}
     * whenever it is not told otherwise, so a Merge or fast-forward-only pull used to
     * rebase anyway in a repository configured for rebasing - the menu said one thing
     * and history got the other. Every mode now states it. Merge leaves the fast-forward
     * mode unset on purpose, so a configured {@code pull.ff=only} or {@code merge.ff}
     * still applies, the same as {@code git pull --no-rebase}.
     */
    public GitResult pull(PullMode mode) {
        if (git() == null) return GitResult.failed("No repository open");
        // A pull that only fast-forwards makes no commit and needs no identity - git
        // allows it with none configured - so a refusal narrows it to fast-forward-only
        // rather than refusing the pull outright.
        GitResult pulled = ctx.asGit(() -> pullWithJGit(mode), refusal -> {
            GitResult ff = pullWithJGit(PullMode.FF_ONLY);
            return ff.isOk() ? ff : GitResult.refused(ff.getMessage()
                    + ". This pull needs a commit, which cannot be made here: " + refusal, ff.getRefusal());
        });
        // Outside asGit: nothing may write the repository's config while the effective
        // identity is standing in it, and a submodule update is not this method's to vouch for.
        if (pulled.isOk()) structure().maybeAutoUpdateSubmodules();
        return pulled;
    }

    private GitResult pullWithJGit(PullMode mode) {
        try {
            if (mode == PullMode.MERGE) {
                GitResult refused = integration().refuseRealMergeOverStagedChanges();
                if (refused != null) return refused;
            }
            var cmd = git().pull();
            switch (mode) {
                case REBASE -> cmd.setRebase(true);
                case FF_ONLY -> {
                    cmd.setRebase(false);
                    cmd.setFastForward(MergeCommand.FastForwardMode.FF_ONLY);
                }
                case MERGE -> cmd.setRebase(false);
            }
            PullResult result = ctx.overNetwork(cmd);
            if (result.isSuccessful()) {
                return GitResult.ok("Pull complete");
            }
            MergeResult mr = result.getMergeResult();
            if (mr != null && mr.getConflicts() != null && !mr.getConflicts().isEmpty()) {
                return GitResult.conflict("Pull stopped with conflicts", List.copyOf(mr.getConflicts().keySet()));
            }
            RebaseResult rr = result.getRebaseResult();
            if (rr != null && !rr.getStatus().isSuccessful()) {
                return rebases().describeUnfinishedRebase(rr, "pull with rebase");
            }
            if (mr != null) {
                return integration().describeFailedMerge(mr, "Pull", "merge");
            }
            return GitResult.failed("Pull failed");
        } catch (Exception e) {
            return failure("Pull failed: ", e);
        }
    }

    /** Where a push of {@code branch} goes; see {@link PushTarget}. */
    private PushTarget pushTarget(String branch, String remote) {
        var config = repository().getConfig();
        return PushTarget.resolve(branch, remote, getRemoteNames(),
                (section, subsection, name) -> config.getString(section, subsection, name));
    }

    /**
     * The commit the push target is known here to have for the current branch.
     *
     * <p>Only the remote-tracking ref counts. Asking the remote directly would produce an
     * id too, but leasing against it would defeat the point: force-with-lease exists so
     * that a push cannot discard commits the user has never seen, and an id fetched a
     * millisecond ago has not been seen either.
     *
     * <p>The lease is taken on the branch the push actually writes - see
     * {@link PushTarget} - and carries whether the leased commit was ever part of this
     * branch, which is what stops an auto-fetched commit being forced away unseen.
     */
    public PushLease pushLease(String remote) {
        // Both: remoteHasBranch needs the porcelain, and a lease that cannot be
        // established has to read as "none", never as "no branch there".
        if (repository() == null || git() == null) return null;
        try {
            if (branches().isHeadDetached()) return null;
            String branch = repository().getBranch();
            if (branch == null || branch.isBlank()) return null;
            PushTarget target = pushTarget(branch, remote);

            ObjectId tracked = repository().resolve(target.trackingRef());
            if (tracked != null) {
                return withIntegration(PushLease.at(target.remote(), branch,
                        target.remoteBranch(), tracked.getName()));
            }
            // No tracking ref. Either the branch has never been published - in which case
            // there is nothing to overwrite and no force is needed - or it exists on the
            // remote and this clone simply has not fetched it, which is precisely when
            // forcing is unsafe. Only the remote can tell the two apart.
            return remoteHasBranch(target.remote(), target.remoteBranch())
                    ? null
                    : PushLease.absent(target.remote(), branch, target.remoteBranch());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not determine a push lease for " + remote, e);
            return null;
        }
    }

    /**
     * The same question {@code git push --force-if-includes} asks: was the leased commit
     * ever part of the local branch - reachable from its tip, or from any commit its
     * reflog records it at? If not, it arrived by a fetch nobody integrated, and forcing
     * would discard it unseen.
     */
    private PushLease withIntegration(PushLease lease) throws IOException {
        if (lease.expectsNoRemoteBranch()) return lease;
        ObjectId expected = ObjectId.fromString(lease.expectedObjectId());
        ObjectId head = repository().resolve(Constants.R_HEADS + lease.branch());
        if (head == null) return lease.withIntegration(false, List.of());

        boolean integrated = reachable(expected, head);
        if (!integrated) {
            // Every position the reflog remembers, asked in one walk. Asking each entry
            // separately walked the branch's whole history again per entry - a long
            // reflog on a large repository made confirming a force push take seconds.
            try (RevWalk walk = new RevWalk(repository())) {
                RevCommit leased = walk.parseCommit(expected);
                java.util.Set<ObjectId> seen = new java.util.HashSet<>();
                List<RevCommit> positions = new ArrayList<>();
                for (org.eclipse.jgit.lib.ReflogEntry entry
                        : git().reflog().setRef(Constants.R_HEADS + lease.branch()).call()) {
                    ObjectId was = entry.getNewId();
                    if (was == null || was.equals(ObjectId.zeroId()) || !seen.add(was)) continue;
                    try {
                        positions.add(walk.parseCommit(was));
                    } catch (org.eclipse.jgit.errors.MissingObjectException pruned) {
                        // A position gc has since collected cannot reach anything.
                    }
                }
                integrated = !positions.isEmpty() && walk.isMergedIntoAnyCommit(leased, positions);
            } catch (Exception e) {
                // No reflog (core.logAllRefUpdates=false) is the same as git's answer:
                // nothing proves the commit was ever here.
                LOGGER.log(Level.FINE, "Could not read the reflog of " + lease.branch(), e);
            }
        }
        return lease.withIntegration(integrated, commitsOnlyIn(expected, head));
    }

    /** Whether {@code ancestor} is {@code descendant} or reachable from it. */
    private boolean reachable(ObjectId ancestor, ObjectId descendant) throws IOException {
        if (ancestor.equals(descendant)) return true;
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit a = walk.parseCommit(ancestor);
            RevCommit d = walk.parseCommit(descendant);
            return walk.isMergedInto(a, d);
        } catch (org.eclipse.jgit.errors.MissingObjectException e) {
            return false;
        }
    }

    /** {@code git log --format="%h %s" <tip> ^<exclude>}, capped: what a force push discards. */
    private List<String> commitsOnlyIn(ObjectId tip, ObjectId exclude) throws IOException {
        List<String> lines = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository())) {
            walk.markStart(walk.parseCommit(tip));
            walk.markUninteresting(walk.parseCommit(exclude));
            for (RevCommit commit : walk) {
                if (lines.size() == PushLease.DISCARD_LIST_LIMIT) {
                    lines.add("…");
                    break;
                }
                lines.add(commit.abbreviate(7).name() + " " + commit.getShortMessage());
            }
        }
        return lines;
    }

    /** Whether {@code remote} advertises {@code refs/heads/<branch>} right now. */
    private boolean remoteHasBranch(String remote, String branch) throws Exception {
        Collection<Ref> refs = ctx.overNetwork(git().lsRemote()
                .setRemote(remote)
                .setHeads(true)
                .setTags(false));
        for (Ref ref : refs) {
            if ((Constants.R_HEADS + branch).equals(ref.getName())) return true;
        }
        return false;
    }

    /**
     * Pushes the current branch, forcing only within {@code lease}.
     *
     * @param remote       remote name, or {@code null} to follow the branch's configuration
     * @param lease        {@code null} for an ordinary push; otherwise what the remote
     *                     branch must still be, captured by {@link #pushLease}
     * @param includeTags  also push annotated tags pointing into the pushed history
     * @param setUpstream  record the pushed branch as the upstream even if one exists
     */
    public GitResult push(String remote, PushLease lease, boolean includeTags, boolean setUpstream) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            String branch = repository().getBranch();
            // Detached HEAD: getBranch() answers with the commit id, and building
            // "refs/heads/<id>:refs/heads/<id>" out of it asks the remote for a branch
            // named after a hash. Say what is actually wrong instead.
            if (branches().isHeadDetached()) {
                return GitResult.failed("HEAD is detached, so there is no branch to push. "
                        + "Create a branch here first, then push it.");
            }
            PushTarget target = pushTarget(branch,
                    remote == null && lease != null ? lease.remote() : remote);
            if (lease != null) {
                if (!branch.equals(lease.branch())) {
                    // The branch changed between confirmation and push; the lease describes a
                    // ref nobody is pushing any more.
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
            String unrunnable = prePushHookThisEngineCannotRun();
            if (unrunnable != null) return GitResult.failed(unrunnable);

            var cmd = git().push().setRemote(target.remote());

            List<RefSpec> specs = new ArrayList<>();
            RefSpec spec = new RefSpec(Constants.R_HEADS + branch + ":" + target.remoteRef());
            if (lease != null && !lease.expectsNoRemoteBranch()) {
                specs.add(spec.setForceUpdate(true));
                cmd.setRefLeaseSpecs(new org.eclipse.jgit.transport.RefLeaseSpec(
                        lease.ref(), lease.expectedObjectId()));
            } else {
                // Either an ordinary push, or a lease that says the branch is not there
                // yet - and publishing a new branch is a fast-forward, not an overwrite.
                // If it appeared meanwhile the push is rejected, which is the point.
                specs.add(spec);
            }
            if (includeTags) {
                // --follow-tags, not --tags: only annotated tags pointing into what is
                // being pushed. Pushing every tag made one stale local tag - the remote
                // has v1.0 somewhere else - turn the whole push into "rejected".
                for (String tag : annotatedTagsReachableFrom(Constants.R_HEADS + branch)) {
                    specs.add(new RefSpec(tag + ":" + tag));
                }
            }
            cmd.setRefSpecs(specs);

            List<RemoteRefUpdate> rejected = notLanded(ctx.overNetwork(cmd));
            List<RemoteRefUpdate> tagRejects = rejected.stream()
                    .filter(update -> update.getRemoteName().startsWith(Constants.R_TAGS))
                    .toList();
            rejected.removeAll(tagRejects);
            if (!rejected.isEmpty()) {
                return GitResult.failed("Push rejected - " + describeRejections(rejected));
            }
            String pushed = "Pushed " + branch + " to " + target.remote()
                    + (target.remoteBranch().equals(branch) ? "" : "/" + target.remoteBranch());
            if (setUpstream || target.publish()) {
                // The commits are on the remote by now, whatever happens to the config. A
                // failed save - a locked .git/config, a read-only one - used to fall into
                // the catch below and report "Push failed", so the user pushed again to a
                // remote that already had everything.
                try {
                    branches().setUpstream(branch, target);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Pushed, but could not record the upstream of " + branch, e);
                    pushed += ", but " + branch + "'s upstream could not be recorded ("
                            + describe(e) + "). Set it from the branch's menu once the"
                            + " repository's config can be written";
                }
            }
            if (!tagRejects.isEmpty()) {
                // The branch went; only these tags did not, because the remote already has
                // a tag of that name somewhere else. That is worth knowing, not a failure.
                pushed += ". Not pushed, because the remote already has a different tag of"
                        + " the same name: " + String.join(", ", tagRejects.stream()
                                .map(update -> Repository.shortenRefName(update.getRemoteName()))
                                .toList());
            }
            return GitResult.ok(pushed);
        } catch (Exception e) {
            return GitResult.failed("Push failed: " + CredentialsManager.getInstance().withAccountHint(describe(e)));
        }
    }

    /**
     * Annotated tags whose target commit is reachable from {@code tip}: what
     * {@code --follow-tags} sends.
     *
     * <p>One walk from the tip, ending as soon as every tagged commit has turned up. An
     * {@code isMergedInto} per tag walked the history again for each one, so a repository
     * with a few hundred release tags paid a few hundred history walks on every push.
     */
    private List<String> annotatedTagsReachableFrom(String tip) throws IOException {
        List<String> tags = new ArrayList<>();
        ObjectId tipId = repository().resolve(tip);
        if (tipId == null) return tags;
        try (RevWalk walk = new RevWalk(repository())) {
            Map<ObjectId, List<String>> tagsByCommit = new java.util.HashMap<>();
            for (Ref ref : repository().getRefDatabase().getRefsByPrefix(Constants.R_TAGS)) {
                if (ref.getObjectId() == null) continue;
                RevObject object = walk.parseAny(ref.getObjectId());
                if (!(object instanceof RevTag tag)) continue;   // lightweight: not followed
                RevObject target = walk.peel(tag);
                if (!(target instanceof RevCommit commit)) continue;
                tagsByCommit.computeIfAbsent(commit.copy(), _ -> new ArrayList<>()).add(ref.getName());
            }
            if (tagsByCommit.isEmpty()) return tags;
            walk.setRetainBody(false);
            walk.markStart(walk.parseCommit(tipId));
            for (RevCommit commit : walk) {
                List<String> here = tagsByCommit.remove(commit);
                if (here != null) {
                    tags.addAll(here);
                    if (tagsByCommit.isEmpty()) break;
                }
            }
        }
        java.util.Collections.sort(tags);
        return tags;
    }

    /**
     * Why this engine must not push here, or {@code null} when it may: a {@code pre-push}
     * hook is installed and this platform cannot run hooks for JGit.
     *
     * <p>JGit runs {@code pre-push} itself through the platform FS, and a plain
     * {@code FS_Win32} with no {@code sh} answers NOT_SUPPORTED and pushes anyway - so a
     * hook written to stop a push (secrets scanning, "never push to main") was skipped
     * without a word. The commit path already refuses in the same situation; pushing
     * has to as well. Kept separate from the commit guard so the two can change
     * independently: they share only the capability probe.
     */
    private String prePushHookThisEngineCannotRun() {
        try {
            FS fs = repository().getFS();
            if (fs == null || fs.findHook(repository(), org.eclipse.jgit.hooks.PrePushHook.NAME) == null) {
                return null;
            }
            if (GitService.platformRunsHooks.test(repository())) return null;
            return "This repository has a pre-push hook, but the JGit engine cannot run hooks"
                    + " on this system, so pushing here would skip it without saying so. Switch"
                    + " to the Native Git engine in Settings to push with the hook running.";
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not determine whether the pre-push hook can run", e);
            return null;
        }
    }

    /** Pushes a delete refspec for the tag - leaves the local tag untouched. */
    public GitResult deleteRemoteTag(String remoteName, String tagName) {
        if (git() == null) return GitResult.failed("No repository open");
        if (tagName == null || tagName.isBlank()) return GitResult.failed("Tag name cannot be empty");
        String target = (remoteName == null || remoteName.isBlank()) ? "origin" : remoteName;
        // Deleting a tag is a push, and pre-push hooks see it like any other.
        String unrunnable = prePushHookThisEngineCannotRun();
        if (unrunnable != null) return GitResult.failed(unrunnable);
        try {
            List<RemoteRefUpdate> rejected = notLanded(ctx.overNetwork(git().push()
                    .setRemote(target)
                    .setRefSpecs(new RefSpec(":" + Constants.R_TAGS + tagName))));
            if (!rejected.isEmpty()) {
                return GitResult.failed("Could not delete " + tagName + " on " + target
                        + " - " + describeRejections(rejected));
            }
            return GitResult.ok("Deleted " + tagName + " from " + target);
        } catch (Exception e) {
            return GitResult.failed("Could not delete remote tag: " + describe(e));
        }
    }

    public GitResult pushTag(String remoteName, String tagName) {
        if (git() == null) return GitResult.failed("No repository open");
        if (tagName == null || tagName.isBlank()) return GitResult.failed("Tag name cannot be empty");
        String target = (remoteName == null || remoteName.isBlank()) ? "origin" : remoteName;
        String unrunnable = prePushHookThisEngineCannotRun();
        if (unrunnable != null) return GitResult.failed(unrunnable);
        try {
            List<RemoteRefUpdate> rejected = notLanded(ctx.overNetwork(git().push()
                    .setRemote(target)
                    .setRefSpecs(new RefSpec(Constants.R_TAGS + tagName))));
            if (!rejected.isEmpty()) {
                return GitResult.failed("Could not push " + tagName + " to " + target
                        + " - " + describeRejections(rejected));
            }
            return GitResult.ok("Pushed " + tagName + " to " + target);
        } catch (Exception e) {
            return GitResult.failed("Could not push tag: " + describe(e));
        }
    }
}
