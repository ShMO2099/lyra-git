package com.jfx.git.core;

import com.jfx.git.model.RefInfo;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Branches, tags, upstreams and ahead/behind tracking, for {@link GitService}.
 */
final class JGitBranches extends JGitPart {

    JGitBranches(JGitRepo ctx) {
        super(ctx);
    }

    // ------------------------------------------------------------------
    // Branches
    // ------------------------------------------------------------------

    public List<RefInfo> getLocalBranches() {
        if (git() == null) return List.of();
        try {
            return git().branchList().call().stream().map(this::toRefInfo).toList();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list local branches", e);
            return List.of();
        }
    }

    /**
     * Remote-tracking branches: not a remote's symbolic {@code HEAD} ({@code origin/HEAD}),
     * which points at one of the others and is no branch, nor a ref the app fabricated.
     */
    public List<RefInfo> getRemoteBranches() {
        if (git() == null) return List.of();
        try {
            return git().branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
                    .stream()
                    .filter(ref -> !ref.isSymbolic() && !GitBackend.isSyntheticRemoteRef(ref.getName()))
                    .map(this::toRefInfo).toList();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list remote branches", e);
            return List.of();
        }
    }

    /**
     * Converts a JGit {@link Ref} to a backend-agnostic {@link RefInfo}.
     *
     * <p>The ref is peeled first. {@code RefInfo.targetHash} is the key of the graph's
     * {@code refsByHash} decoration map, so an unpeeled annotated tag carries the tag
     * <em>object</em>'s SHA - a hash no commit row has - and the tag then decorates
     * nothing. Refs handed back by JGit's list commands are generally not peeled already,
     * so relying on {@link Ref#getPeeledObjectId()} being populated is not enough.
     */
    private RefInfo toRefInfo(Ref ref) {
        String full = ref.getName();
        String short_ = full;
        if (full.startsWith(Constants.R_HEADS))   short_ = full.substring(Constants.R_HEADS.length());
        else if (full.startsWith(Constants.R_REMOTES)) short_ = full.substring(Constants.R_REMOTES.length());
        else if (full.startsWith(Constants.R_TAGS))    short_ = full.substring(Constants.R_TAGS.length());
        Ref peeled = ref;
        if (repository() != null && ref.getPeeledObjectId() == null) {
            try {
                peeled = repository().getRefDatabase().peel(ref);
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Could not peel " + full, e);
                peeled = ref;
            }
        }
        ObjectId target = peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : peeled.getObjectId();
        return new RefInfo(full, short_, target != null ? target.getName() : null);
    }

    /**
     * The branch HEAD is on, or blank while HEAD is detached - not the commit's hash,
     * which {@code Repository.getBranch()} answers then. An unborn branch is still the
     * current branch.
     */
    public String getCurrentBranch() {
        if (repository() == null) return "";
        try {
            Ref head = repository().exactRef(Constants.HEAD);
            if (head == null || !head.isSymbolic()) return "";
            String target = head.getTarget().getName();
            return target.startsWith(Constants.R_HEADS) ? target.substring(Constants.R_HEADS.length()) : "";
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not read current branch", e);
            return "";
        }
    }

    /**
     * Ahead/behind for every local branch, keyed by short name.
     *
     * <h2>Why this is not left to the interface default</h2>
     * The default answers with an empty map, which the sidebar reads as "no branch is
     * ahead or behind anything" and draws no arrows at all. That is indistinguishable
     * from a repository perfectly in sync, so the JGit engine quietly lost the whole
     * feature rather than showing it was missing - the same way {@code getChangesInCommit}
     * did before it was implemented here.
     *
     * <p>Only branches that are actually ahead or behind get an entry, matching
     * {@code NativeGitBackend.getBranchTracking}: the sidebar draws a badge for whatever
     * it finds, so an entry of {@code [0, 0]} would put a zero beside every branch.
     *
     * <p>{@link BranchTrackingStatus#of} walks the two sides per branch, so this is
     * proportional to the number of branches rather than one query for all of them. The
     * CLI engine gets the whole answer from a single {@code for-each-ref}; JGit has no
     * equivalent, and the walk is over already-loaded refs.
     */
    public java.util.Map<String, int[]> getBranchTracking() {
        if (git() == null || repository() == null) return java.util.Map.of();
        java.util.Map<String, int[]> tracking = new java.util.HashMap<>();
        try {
            for (Ref ref : git().branchList().call()) {
                String name = Repository.shortenRefName(ref.getName());
                BranchTrackingStatus status = BranchTrackingStatus.of(repository(), name);
                if (status == null) {
                    continue;   // no upstream, or the upstream has been deleted
                }
                int ahead = status.getAheadCount();
                int behind = status.getBehindCount();
                if (ahead > 0 || behind > 0) {
                    tracking.put(name, new int[]{ahead, behind});
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read per-branch tracking", e);
        }
        return tracking;
    }

    /**
     * {@code [ahead, behind]} relative to the current branch's upstream, and {@code [0, 0]}
     * when there is none - the {@link BranchOps#getTrackingStatus} contract, and what the
     * CLI engine answers. This used to return {@code null} instead, so every caller had
     * to know that one engine meant "no upstream" by it and the others never did.
     */
    public int[] getTrackingStatus() {
        if (repository() == null) return new int[]{0, 0};
        try {
            BranchTrackingStatus status = BranchTrackingStatus.of(repository(), repository().getBranch());
            if (status == null) return new int[]{0, 0};
            return new int[]{status.getAheadCount(), status.getBehindCount()};
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "No tracking status available", e);
            return new int[]{0, 0};
        }
    }

    public String symbolicRefTarget(String fullName) {
        if (repository() == null) return null;
        try {
            Ref ref = repository().getRefDatabase().exactRef(fullName);
            return ref != null && ref.isSymbolic() ? ref.getTarget().getName() : null;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read " + fullName, e);
            return null;
        }
    }

    public boolean isHeadDetached() {
        if (repository() == null) return false;
        try {
            Ref head = repository().getRefDatabase().exactRef(Constants.HEAD);
            // An unborn branch keeps HEAD symbolic (pointing at a ref that does not
            // exist yet), so it correctly reads as attached.
            return head != null && !head.isSymbolic();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read HEAD", e);
            return false;
        }
    }

    public boolean isDetachedHeadOrphanRisk() {
        if (!isHeadDetached()) return false;
        try {
            ObjectId headId = repository().resolve(Constants.HEAD);
            if (headId == null) return false;
            // isMergedIntoAnyCommit rather than walking everything the tips reach: that
            // walk only ended early when HEAD turned up, so the answer this exists to give -
            // "no branch or tag has it" - always cost the repository's entire history.
            try (RevWalk walk = new RevWalk(repository())) {
                RevCommit head = walk.parseCommit(headId);
                List<RevCommit> tips = new ArrayList<>();
                for (Ref ref : repository().getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                    if (ref.getObjectId() != null) {
                        tips.add(walk.parseCommit(ref.getObjectId()));
                    }
                }
                for (Ref ref : repository().getRefDatabase().getRefsByPrefix(Constants.R_TAGS)) {
                    ObjectId peeled = repository().getRefDatabase().peel(ref).getPeeledObjectId();
                    ObjectId tip = peeled != null ? peeled : ref.getObjectId();
                    if (tip != null) {
                        try {
                            tips.add(walk.parseCommit(tip));
                        } catch (Exception notACommit) {
                            // Lightweight tag pointing at something that isn't a commit
                            // (rare) — nothing to walk from, safe to skip.
                        }
                    }
                }
                if (tips.isEmpty()) return true;
                return !walk.isMergedIntoAnyCommit(head, tips);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not determine detached-HEAD orphan risk", e);
            return false;
        }
    }

    public GitResult checkoutBranch(String branchName) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            git().checkout().setName(branchName).call();
            structure().maybeAutoUpdateSubmodules();
            return GitResult.ok("Checked out " + branchName);
        } catch (Exception e) {
            return failure("Checkout failed: ", e);
        }
    }

    /**
     * Checks out a remote branch as a local tracking branch.
     *
     * <p>The old code passed {@code "origin/" + item} where the item already carried the
     * remote prefix, producing {@code origin/origin/main}; remote checkout never worked.
     * Checking out the remote ref directly would also leave a detached HEAD.
     *
     * @param remoteBranch e.g. {@code origin/feature}
     */
    public GitResult checkoutRemoteBranch(String remoteBranch) {
        if (git() == null) return GitResult.failed("No repository open");
        // What follows the remote, which may have a slash in its own name: team/core/topic
        // is topic, not core/topic.
        String localName = com.jfx.git.model.RefNames.branchOf(remoteBranch, remotes().getRemoteNames());
        try {
            boolean exists = repository().findRef(Constants.R_HEADS + localName) != null;
            if (exists) {
                git().checkout().setName(localName).call();
                structure().maybeAutoUpdateSubmodules();
                return GitResult.ok("Checked out existing branch " + localName);
            }
            // Git's order, not CheckoutCommand's. CheckoutCommand creates the branch and then
            // switches the files, so a switch refused for local changes left the branch
            // behind, upstream and all: cancelling "Stash and check out" still added one.
            // Git checks the name, switches the files - where a refusal happens - and only
            // then creates the branch and moves HEAD onto it.
            if (!Repository.isValidRefName(Constants.R_HEADS + localName)) {
                return GitResult.failed("Checkout failed: " + localName + " is not a valid branch name");
            }
            ObjectId target = repository().resolve(remoteBranch);
            if (target == null) {
                return GitResult.failed("Checkout failed: there is no remote branch " + remoteBranch);
            }
            String from = repository().getBranch();
            switchFilesTo(target);
            git().branchCreate()
                    .setName(localName)
                    .setStartPoint(remoteBranch)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                    .call();
            RefUpdate head = repository().updateRef(Constants.HEAD);
            head.setRefLogMessage("checkout: moving from " + from + " to " + localName, false);
            RefUpdate.Result moved = head.link(Constants.R_HEADS + localName);
            if (moved != RefUpdate.Result.NEW && moved != RefUpdate.Result.FORCED
                    && moved != RefUpdate.Result.NO_CHANGE) {
                return GitResult.failed("Checkout failed: HEAD could not be moved to " + localName
                        + " (" + moved + ")");
            }
            structure().maybeAutoUpdateSubmodules();
            return GitResult.ok("Created tracking branch " + localName + " from " + remoteBranch);
        } catch (Exception e) {
            return failure("Checkout failed: ", e);
        }
    }

    /**
     * Brings the index and working tree from HEAD's commit to {@code commit}'s, as
     * CheckoutCommand does and with the same refusal - JGit's "Checkout conflict with
     * files" - when that would overwrite local changes. Moves no ref.
     */
    private void switchFilesTo(ObjectId commit) throws IOException {
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId headId = repository().resolve(Constants.HEAD);
            ObjectId headTree = headId == null ? null : walk.parseCommit(headId).getTree();
            ObjectId targetTree = walk.parseCommit(commit).getTree();
            org.eclipse.jgit.dircache.DirCache index = repository().lockDirCache();
            try {
                var checkout = new org.eclipse.jgit.dircache.DirCacheCheckout(
                        repository(), headTree, index, targetTree);
                checkout.setFailOnConflict(true);
                checkout.checkout();
            } finally {
                index.unlock();
            }
        }
    }

    public GitResult createBranch(String name, String startPoint, boolean checkout) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            CreateBranchCommand cmd = git().branchCreate().setName(name);
            if (startPoint != null && !startPoint.isBlank()) {
                cmd.setStartPoint(startPoint);
            }
            cmd.call();
            if (checkout) {
                try {
                    git().checkout().setName(name).call();
                } catch (Exception refused) {
                    // git's `checkout -b` makes no branch when the switch is refused, so a
                    // retry - the auto-stash's - can make it again.
                    try {
                        git().branchDelete().setBranchNames(name).setForce(true).call();
                    } catch (Exception ignored) {
                        // The refusal is what the caller needs to hear.
                    }
                    throw refused;
                }
            }
            return GitResult.ok("Created branch " + name);
        } catch (Exception e) {
            return failure("Could not create branch: ", e);
        }
    }

    public GitResult deleteBranch(String name, boolean force) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            git().branchDelete().setBranchNames(name).setForce(force).call();
            return GitResult.ok("Deleted branch " + name);
        } catch (org.eclipse.jgit.api.errors.NotMergedException e) {
            return GitResult.refused("Branch " + name + " is not fully merged. Use force delete to discard it.",
                    new Refusal.NotFullyMerged(name));
        } catch (Exception e) {
            return GitResult.failed("Could not delete branch: " + describe(e));
        }
    }

    public GitResult renameBranch(String oldName, String newName) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            git().branchRename().setOldName(oldName).setNewName(newName).call();
            return GitResult.ok("Renamed " + oldName + " to " + newName);
        } catch (Exception e) {
            return GitResult.failed("Could not rename branch: " + describe(e));
        }
    }

    public String getUpstream(String localBranch) {
        if (repository() == null || localBranch == null || localBranch.isBlank()) return null;
        try {
            String tracking = new org.eclipse.jgit.lib.BranchConfig(repository().getConfig(), localBranch)
                    .getTrackingBranch();
            return tracking == null ? null : Repository.shortenRefName(tracking);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read the upstream of " + localBranch, e);
            return null;
        }
    }

    public Map<String, String> getUpstreams() {
        if (git() == null || repository() == null) return Map.of();
        Map<String, String> upstreams = new java.util.LinkedHashMap<>();
        try {
            for (Ref ref : repository().getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                String branch = ref.getName().substring(Constants.R_HEADS.length());
                String upstream = getUpstream(branch);
                if (upstream != null) upstreams.put(branch, upstream);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read the branches' upstreams", e);
        }
        return upstreams;
    }

    public GitResult setUpstream(String localBranch, String upstream) {
        if (git() == null) return GitResult.failed("No repository open");
        String local = localBranch == null ? "" : localBranch.trim();
        String tracked = upstream == null ? "" : upstream.trim();
        try {
            if (local.isEmpty() || repository().exactRef(Constants.R_HEADS + local) == null) {
                return GitResult.failed("No such branch: " + local);
            }
            // The longest remote name that fits: a remote may itself have a slash in it.
            List<String> remotes = remotes().getRemoteNames();
            String remote = com.jfx.git.model.RefNames.remoteOf(tracked, remotes);
            if (!remotes.contains(remote) || repository().exactRef(Constants.R_REMOTES + tracked) == null) {
                return GitResult.failed("No such remote branch: " + tracked);
            }
            setUpstream(local, new PushTarget(remote, tracked.substring(remote.length() + 1), false));
            return GitResult.ok(local + " now tracks " + tracked);
        } catch (Exception e) {
            return GitResult.failed("Could not set the upstream: " + describe(e));
        }
    }

    public GitResult unsetUpstream(String localBranch) {
        if (git() == null) return GitResult.failed("No repository open");
        String local = localBranch == null ? "" : localBranch.trim();
        try {
            if (local.isEmpty() || repository().exactRef(Constants.R_HEADS + local) == null) {
                return GitResult.failed("No such branch: " + local);
            }
            var config = repository().getConfig();
            String section = org.eclipse.jgit.lib.ConfigConstants.CONFIG_BRANCH_SECTION;
            if (config.getString(section, local, "remote") == null
                    && config.getString(section, local, "merge") == null) {
                return GitResult.failed("Branch " + local + " has no upstream");
            }
            config.unset(section, local, "remote");
            config.unset(section, local, "merge");
            config.save();
            return GitResult.ok(local + " no longer tracks a remote branch");
        } catch (Exception e) {
            return GitResult.failed("Could not unset the upstream: " + describe(e));
        }
    }

    /**
     * Gone means configured but missing: {@link org.eclipse.jgit.lib.BranchConfig} works
     * the remote-tracking branch out from the remote's fetch refspec, as git's {@code
     * %(upstream)} does, and the ref it names is not there.
     */
    public java.util.Set<String> getGoneBranches() {
        if (repository() == null) return java.util.Set.of();
        java.util.Set<String> gone = new java.util.TreeSet<>();
        try {
            for (Ref ref : repository().getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                String branch = ref.getName().substring(Constants.R_HEADS.length());
                String tracking = new org.eclipse.jgit.lib.BranchConfig(repository().getConfig(), branch)
                        .getTrackingBranch();
                if (tracking != null && repository().exactRef(tracking) == null) {
                    gone.add(branch);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read which upstreams have gone", e);
        }
        return gone;
    }

    public List<String> getBranchesMergedInto(String target) {
        if (repository() == null || target == null || target.isBlank()) return List.of();
        List<String> merged = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId targetId = repository().resolve(target + "^{commit}");
            if (targetId == null) return List.of();
            RevCommit into = walk.parseCommit(targetId);
            for (Ref ref : repository().getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                if (ref.getObjectId() == null) continue;
                RevObject tip = walk.parseAny(ref.getObjectId());
                if (!(tip instanceof RevCommit commit)) continue;
                walk.reset();
                if (walk.isMergedInto(commit, into)) {
                    merged.add(ref.getName().substring(Constants.R_HEADS.length()));
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read the branches merged into " + target, e);
            return List.of();
        }
        merged.sort(null);
        return merged;
    }

    void setUpstream(String branch, PushTarget target) throws IOException {
        var config = repository().getConfig();
        config.setString(org.eclipse.jgit.lib.ConfigConstants.CONFIG_BRANCH_SECTION, branch, "remote",
                target.remote());
        config.setString(org.eclipse.jgit.lib.ConfigConstants.CONFIG_BRANCH_SECTION, branch, "merge",
                target.remoteRef());
        config.save();
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    public List<RefInfo> getTags() {
        if (git() == null) return List.of();
        try {
            return git().tagList().call().stream().map(this::toRefInfo).toList();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list tags", e);
            return List.of();
        }
    }

    public GitResult createTag(String name, String message, String commitish) {
        if (git() == null) return GitResult.failed("No repository open");
        // A lightweight tag is only a ref, so it needs no identity; git makes one without.
        if (message == null || message.isBlank()) return createTagWithJGit(name, message, commitish);
        return ctx.asGit(() -> createTagWithJGit(name, message, commitish), null);
    }

    private GitResult createTagWithJGit(String name, String message, String commitish) {
        try (RevWalk walk = new RevWalk(repository())) {
            var cmd = git().tag().setName(name);
            if (message != null && !message.isBlank()) {
                cmd.setMessage(message).setAnnotated(true);
            } else {
                cmd.setAnnotated(false);
            }
            if (commitish != null && !commitish.isBlank()) {
                ObjectId id = repository().resolve(commitish);
                if (id == null) return GitResult.failed("Unknown commit: " + commitish);
                cmd.setObjectId(walk.parseCommit(id));
            }
            cmd.call();
            return GitResult.ok("Created tag " + name);
        } catch (Exception e) {
            return GitResult.failed("Could not create tag: " + describe(e));
        }
    }

    public GitResult deleteTag(String name) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            git().tagDelete().setTags(name).call();
            return GitResult.ok("Deleted tag " + name);
        } catch (Exception e) {
            return GitResult.failed("Could not delete tag: " + describe(e));
        }
    }

    /** Reads the exact object stored in a tag ref, without peeling annotated tags. */
    public String getRawTagObjectId(String tagName) {
        if (repository() == null) return null;
        String refName = Constants.R_TAGS + (tagName == null ? "" : tagName);
        if (!Repository.isValidRefName(refName)) {
            throw new IllegalArgumentException("Invalid tag name: " + tagName);
        }
        try {
            Ref ref = repository().exactRef(refName);
            return ref == null || ref.getObjectId() == null ? null : ref.getObjectId().getName();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read tag ref " + tagName, e);
        }
    }

    /** Creates the tag ref only while absent, pointing at the original raw object. */
    public GitResult restoreTagRef(String tagName, String objectId) {
        if (repository() == null) return GitResult.failed("No repository open");
        String refName = Constants.R_TAGS + (tagName == null ? "" : tagName);
        if (!Repository.isValidRefName(refName)) {
            return GitResult.failed("Invalid tag name: " + tagName);
        }
        if (objectId == null || !ObjectId.isId(objectId)) {
            return GitResult.failed("Invalid tag object ID");
        }
        try {
            ObjectId id = ObjectId.fromString(objectId);
            if (!repository().getObjectDatabase().has(id)) {
                return GitResult.failed("Unknown tag object: " + objectId);
            }
            RefUpdate update = repository().updateRef(refName);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.setNewObjectId(id);
            RefUpdate.Result result = update.update();
            return result == RefUpdate.Result.NEW
                    ? GitResult.ok("Restored tag " + tagName)
                    : GitResult.failed("Tag already exists or could not be restored: " + tagName);
        } catch (Exception e) {
            return GitResult.failed("Could not restore tag " + tagName + ": " + describe(e));
        }
    }

    /**
     * Whether moving the tag to {@code newCommitish} only moves it forward. The one tag
     * is read directly - this used to list and peel every tag in the repository to find it.
     */
    public boolean isTagMoveFastForward(String tagName, String newCommitish) {
        if (git() == null || tagName == null || newCommitish == null) return false;
        try (RevWalk walk = new RevWalk(repository())) {
            Ref tag = repository().exactRef(Constants.R_TAGS + tagName);
            if (tag == null || tag.getObjectId() == null) return false;
            ObjectId newId = repository().resolve(newCommitish);
            if (newId == null) return false;
            // parseCommit peels an annotated tag to the commit it names.
            RevCommit current = walk.parseCommit(tag.getObjectId());
            RevCommit target = walk.parseCommit(newId);
            return walk.isMergedInto(current, target);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Moves a tag by deleting and recreating it.
     *
     * <p>The target is resolved <em>before</em> the delete. The pair is not atomic, so a
     * target that turns out not to exist would otherwise leave the user with no tag at
     * all - a silent loss of the thing they were trying to move. If the recreate still
     * fails for some other reason, the old ref is written back to the object it held,
     * which restores an annotated tag exactly (the tag object itself is still in the
     * object database).
     */
    public GitResult moveTag(String tagName, String newCommitish) {
        if (git() == null) return GitResult.failed("No repository open");
        if (tagName == null || tagName.isBlank()) return GitResult.failed("Tag name cannot be empty");
        if (newCommitish == null || newCommitish.isBlank()) {
            return GitResult.failed("No target commit for tag " + tagName);
        }
        String previousObject;
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId newId = repository().resolve(newCommitish);
            if (newId == null) return GitResult.failed("Unknown commit: " + newCommitish);
            walk.parseCommit(newId);
            Ref existing = repository().findRef(Constants.R_TAGS + tagName);
            previousObject = existing == null || existing.getObjectId() == null
                    ? null : existing.getObjectId().getName();
        } catch (Exception e) {
            return GitResult.failed("Could not resolve " + newCommitish + ": " + describe(e));
        }

        String message = tagMessage(tagName);
        GitResult deleted = deleteTag(tagName);
        if (!deleted.isOk()) return deleted;
        GitResult created = createTag(tagName, message, newCommitish);
        if (!created.isOk()) {
            return GitResult.failed("Could not move tag " + tagName + ": " + created.getMessage()
                    + restoreTagSuffix(tagName, previousObject));
        }
        return GitResult.ok("Moved tag " + tagName);
    }

    /**
     * Puts {@code refs/tags/<name>} back on {@code objectId} after a failed move, and
     * reports the outcome as a sentence fragment to append to the failure message.
     */
    private String restoreTagSuffix(String tagName, String objectId) {
        if (objectId == null) return "";
        try {
            RefUpdate update = repository().updateRef(Constants.R_TAGS + tagName);
            update.setNewObjectId(ObjectId.fromString(objectId));
            update.setForceUpdate(true);
            RefUpdate.Result result = update.update();
            boolean restored = result == RefUpdate.Result.NEW
                    || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.NO_CHANGE
                    || result == RefUpdate.Result.FAST_FORWARD;
            if (restored) return " - the tag was left where it was";
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not restore tag " + tagName, e);
        }
        return " - and the tag could not be restored; it pointed at " + objectId;
    }

    /** The annotated tag's own message, or {@code null} for a lightweight tag. */
    private String tagMessage(String tagName) {
        if (git() == null) return null;
        try (RevWalk walk = new RevWalk(repository())) {
            Ref ref = repository().findRef(Constants.R_TAGS + tagName);
            if (ref == null) return null;
            RevObject obj = walk.parseAny(ref.getObjectId());
            return obj instanceof RevTag tag ? tag.getFullMessage() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
