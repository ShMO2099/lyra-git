package com.jfx.git.core;

import com.jfx.git.model.BisectStatus;
import com.jfx.git.model.MergeMode;
import com.jfx.git.model.ResetMode;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

/**
 * Merge, cherry-pick, revert, reset, patches, and finishing or abandoning whatever is in
 * progress, for {@link GitService}.
 */
final class JGitIntegration extends JGitPart {

    JGitIntegration(JGitRepo ctx) {
        super(ctx);
    }

    /**
     * The merge half of a pull refused as {@link #mergeWithJGit} refuses a merge: when
     * something is staged and the pull would make a real merge rather than fast-forward.
     *
     * <p>Whether it would is only known once the upstream has been fetched, so with
     * something staged this fetches first and asks then; the pull itself fetches again,
     * which finds nothing new. With nothing staged - the usual case - none of this runs.
     *
     * @return the refusal, or {@code null} to go ahead with the pull - including when the
     *         branch has no upstream, which the pull itself reports
     */
    GitResult refuseRealMergeOverStagedChanges() throws Exception {
        List<String> staged = staging().stagedPaths();
        if (staged.isEmpty()) return null;
        String branch = repository().getBranch();
        var branchConfig = new org.eclipse.jgit.lib.BranchConfig(repository().getConfig(), branch);
        String remote = branchConfig.getRemote();
        String tracking = branchConfig.getRemoteTrackingBranch();
        if (remote == null || tracking == null) return null;
        if (!".".equals(remote)) {
            ctx.overNetwork(git().fetch().setRemote(remote));
        }
        ObjectId upstream = repository().resolve(tracking);
        if (upstream == null) return null;
        // PullCommand's own choice: pull.ff, else whatever the merge configuration says.
        org.eclipse.jgit.api.MergeCommand.FastForwardMode.Merge pullFf = repository().getConfig().getEnum(
                org.eclipse.jgit.api.MergeCommand.FastForwardMode.Merge.values(),
                org.eclipse.jgit.lib.ConfigConstants.CONFIG_PULL_SECTION, null,
                org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_FF);
        MergeCommand.FastForwardMode ff = pullFf != null
                ? MergeCommand.FastForwardMode.valueOf(pullFf)
                : org.eclipse.jgit.merge.MergeConfig.getConfigForCurrentBranch(repository()).getFastForwardMode();
        return needsMergeCommit(upstream, ff) ? refusedOver("merge", staged) : null;
    }

    /**
     * A merge (or the merge half of a pull) that stopped without conflicts, naming the
     * paths in the way. {@code "Merge failed: FAILED"} said nothing a user could act on,
     * while JGit had the list all along.
     */
    GitResult describeFailedMerge(MergeResult result, String operation, String what) {
        if (result.getFailingPaths() != null && !result.getFailingPaths().isEmpty()) {
            return refusedOver(what, result.getFailingPaths());
        }
        if (result.getCheckoutConflicts() != null && !result.getCheckoutConflicts().isEmpty()) {
            return refusedOver(what, result.getCheckoutConflicts());
        }
        return GitResult.failed(operation + " failed: " + result.getMergeStatus());
    }

    static String inParentheses(List<String> paths) {
        return paths == null || paths.isEmpty() ? "" : " (" + pathList(paths) + ")";
    }

    // ------------------------------------------------------------------
    // History-rewriting operations
    // ------------------------------------------------------------------

    public GitResult merge(String sourceBranch, MergeMode mode, String message) {
        if (git() == null) return GitResult.failed("No repository open");
        // A squash stages the result and stops, so it commits nothing and needs no identity.
        if (mode == MergeMode.SQUASH) return mergeWithJGit(sourceBranch, mode, message);
        return ctx.asGit(() -> mergeWithJGit(sourceBranch, mode, message), refusal ->
                mode != MergeMode.NO_FF && needsNoCommit(sourceBranch)
                        ? mergeWithJGit(sourceBranch, MergeMode.FF_ONLY, message)
                        : GitResult.failed(refusal));
    }

    /** Whether merging {@code source} is a fast-forward or already done - no commit either way. */
    private boolean needsNoCommit(String source) {
        try (RevWalk walk = new RevWalk(repository())) {
            ObjectId theirs = repository().resolve(source);
            ObjectId ours = repository().resolve(Constants.HEAD);
            if (theirs == null) return false;
            if (ours == null) return true;
            RevCommit head = walk.parseCommit(ours);
            RevCommit other = walk.parseCommit(theirs);
            return walk.isMergedInto(head, other) || walk.isMergedInto(other, head);
        } catch (Exception e) {
            return false;
        }
    }

    private GitResult mergeWithJGit(String sourceBranch, MergeMode mode, String message) {
        MergeMode chosen = mode == null ? MergeMode.DEFAULT : mode;
        String custom = message == null || message.isBlank() ? null : message.strip();
        try {
            ObjectId source = repository().resolve(sourceBranch);
            if (source == null) {
                return GitResult.failed("Unknown branch: " + sourceBranch);
            }
            if (needsMergeCommit(source, chosen)) {
                // git refuses a real merge over anything staged, related or not: the
                // merge's result is written to the index, so staged work would either be
                // folded into the merge commit or, on "Abort", reset along with it as if
                // the merge had put it there. JGit's merger fails on a staged edit to a
                // tracked file ("FAILED", and nothing more) but lets a staged new file
                // through, since no side of the merge has it - and Abort then deleted that
                // file from the disk. A fast-forward is a checkout, which carries unrelated
                // staged changes across in git as well - so only a real merge is refused.
                List<String> staged = staging().stagedPaths();
                if (!staged.isEmpty()) {
                    return refusedOver("merge", staged);
                }
            }
            var command = git().merge()
                    .include(source)
                    .setStrategy(MergeStrategy.RECURSIVE)
                    .setMessage(custom != null ? custom : defaultMergeMessage(sourceBranch));
            // DEFAULT passes nothing on. Setting FF for the ordinary case, as this once
            // did, overrode merge.ff and branch.<name>.mergeoptions: a repository
            // configured "merge.ff = only" or "= false" merged differently here than on
            // the command line. Left unset, JGit reads the same configuration git does.
            switch (chosen) {
                case DEFAULT -> { }
                case FF_ONLY -> command.setFastForward(MergeCommand.FastForwardMode.FF_ONLY);
                case NO_FF -> command.setFastForward(MergeCommand.FastForwardMode.NO_FF);
                // FF explicitly, as the CLI engine passes --ff: a configured merge.ff=false
                // would otherwise read as --no-ff, which JGit and git both refuse to
                // combine with a squash.
                case SQUASH -> command.setSquash(true)
                        .setFastForward(MergeCommand.FastForwardMode.FF);
            }
            MergeResult result = command.call();

            MergeResult.MergeStatus st = result.getMergeStatus();
            boolean squash = chosen == MergeMode.SQUASH;
            if (squash) {
                // Nothing squashed means no SQUASH_MSG was written, and one an earlier
                // squash left behind is not this one's to retitle.
                if (st == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                    return GitResult.ok(alreadySquashedMessage(sourceBranch));
                }
                if (st.isSuccessful() || st == MergeResult.MergeStatus.CONFLICTING) {
                    SquashMessage.title(repository().getDirectory(), sourceBranch, custom);
                }
            }
            if (st.isSuccessful()) {
                return GitResult.ok(squash
                        ? squashedMessage(sourceBranch)
                        : "Merged " + sourceBranch + " (" + st + ")");
            }
            if (st == MergeResult.MergeStatus.CONFLICTING) {
                return GitResult.conflict((squash ? "Squash of " : "Merge of ") + sourceBranch
                                + " has conflicts",
                        result.getConflicts() == null ? List.of() : List.copyOf(result.getConflicts().keySet()));
            }
            if (st == MergeResult.MergeStatus.ABORTED) {
                // FF_ONLY that could not fast-forward - asked for by the mode, or by
                // merge.ff = only when the mode left the choice to the configuration.
                return GitResult.failed("Not possible to fast-forward to " + sourceBranch
                        + (chosen == MergeMode.FF_ONLY
                                ? " - the branches have diverged"
                                : " (merge.ff is set to only)")
                        + ", so nothing was merged.");
            }
            return describeFailedMerge(result, "Merge", "merge");
        } catch (Exception e) {
            return failure("Merge failed: ", e);
        }
    }

    /**
     * Whether merging {@code source} in {@code mode} goes through the merger rather than
     * being a fast-forward or nothing at all - the case in which git refuses to start
     * over staged changes.
     *
     * <p>{@code DEFAULT} follows the configuration as JGit's {@code MergeCommand} will
     * ({@code merge.ff}, {@code branch.<name>.mergeoptions}); a squash is a fast-forward
     * where one is possible, as {@code git merge --squash} is.
     */
    private boolean needsMergeCommit(ObjectId source, MergeMode mode) throws IOException {
        return needsMergeCommit(source, switch (mode) {
            case DEFAULT -> org.eclipse.jgit.merge.MergeConfig
                    .getConfigForCurrentBranch(repository()).getFastForwardMode();
            case FF_ONLY -> MergeCommand.FastForwardMode.FF_ONLY;
            case NO_FF -> MergeCommand.FastForwardMode.NO_FF;
            case SQUASH -> MergeCommand.FastForwardMode.FF;
        });
    }

    /** The same question for a fast-forward mode already decided; {@code null} is FF. */
    private boolean needsMergeCommit(ObjectId source, MergeCommand.FastForwardMode ff) throws IOException {
        ObjectId headId = repository().resolve(Constants.HEAD);
        if (headId == null) return false;   // an unborn branch simply takes the commit
        // Fast-forward only either fast-forwards or refuses before touching anything.
        if (ff == MergeCommand.FastForwardMode.FF_ONLY) return false;
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit head = walk.parseCommit(headId);
            RevCommit theirs = walk.parseCommit(source);
            if (walk.isMergedInto(theirs, head)) return false;   // already up to date
            return ff == MergeCommand.FastForwardMode.NO_FF || !walk.isMergedInto(head, theirs);
        }
    }

    /**
     * The message git would give a merge of {@code source}, with
     * {@link DefaultMergeMessage} doing the wording: what kind of ref it is, and the
     * current branch unless {@code merge.suppressDest} leaves it out.
     */
    private String defaultMergeMessage(String source) {
        String kind = null;
        try {
            Ref ref = repository().findRef(source);
            kind = ref == null ? null : ref.getName();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not look up " + source, e);
        }
        String into;
        try {
            into = branches().isHeadDetached() ? Constants.HEAD : repository().getBranch();
        } catch (IOException e) {
            into = null;
        }
        return DefaultMergeMessage.of(source, kind, into,
                java.util.Arrays.asList(repository().getConfig().getStringList("merge", null, "suppressDest")));
    }

    /** What a squash that stopped cleanly reports, on every engine. */
    static String squashedMessage(String sourceBranch) {
        return "Squashed " + sourceBranch + " into the staged changes - commit them to finish";
    }

    /** What a squash of a branch that is already merged reports, on every engine. */
    static String alreadySquashedMessage(String sourceBranch) {
        return "Already up to date with " + sourceBranch + " - nothing to squash";
    }

    public GitResult createPatch(List<String> commits, File out) {
        if (git() == null) return GitResult.failed("No repository open");
        if (commits == null || commits.isEmpty()) return GitResult.failed("No commits to make a patch from");
        if (out == null) return GitResult.failed("No file to save the patch to");
        try (RevWalk walk = new RevWalk(repository())) {
            List<RevCommit> ordered = new ArrayList<>();
            for (String hash : commits) {
                ObjectId id = repository().resolve(hash);
                if (id == null) return GitResult.failed("No such commit: " + shorten(hash));
                RevCommit commit = walk.parseCommit(id);
                if (commit.getParentCount() > 1) {
                    return GitResult.failed(IntegrationOps.mergeHasNoPatch(shorten(commit.getName())));
                }
                String binary = PatchWriter.binaryPath(repository(), commit);
                if (binary != null) {
                    return GitResult.failed(shorten(commit.getName()) + " changes a binary file, " + binary
                            + ". Saving that as a patch needs the Native Git engine.");
                }
                ordered.add(commit);
            }
            // Oldest first. The graph hands commits over newest first, so reversing
            // keeps that order for commits made in the same second.
            java.util.Collections.reverse(ordered);
            ordered.sort(Comparator.comparingInt(RevCommit::getCommitTime));

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            for (RevCommit commit : ordered) {
                PatchWriter.write(repository(), commit, buffer);
            }
            Files.write(out.toPath(), buffer.toByteArray());
            return GitResult.ok(ordered.size() == 1
                    ? "Saved " + shorten(ordered.get(0).getName()) + " as " + out.getName()
                    : "Saved " + ordered.size() + " commits as " + out.getName());
        } catch (Exception e) {
            return GitResult.failed("Could not save the patch: " + describe(e));
        }
    }

    /**
     * Applies a patch to the working tree, staged or not.
     *
     * <p>JGit's apply writes the files and stages them. For
     * {@link com.jfx.git.model.PatchApplyMode#WORKTREE} the staging is taken back off
     * the paths it touched, leaving exactly what {@code git apply} leaves; for
     * {@code INDEX} they are staged explicitly, so the result does not depend on which
     * of those JGit's own command happens to do.
     */
    public GitResult applyPatch(File patch, com.jfx.git.model.PatchApplyMode mode) {
        if (git() == null) return GitResult.failed("No repository open");
        if (patch == null || !patch.isFile()) return GitResult.failed("There is no patch file to apply");
        if (mode == com.jfx.git.model.PatchApplyMode.AM) {
            return GitResult.failed("Applying a patch as commits needs the Native Git engine.");
        }
        try (java.io.InputStream in = new java.io.FileInputStream(patch)) {
            org.eclipse.jgit.api.ApplyResult applied = git().apply().setPatch(in).call();
            java.nio.file.Path base = repository().getWorkTree().toPath();
            List<String> present = new ArrayList<>();
            List<String> gone = new ArrayList<>();
            for (File file : applied.getUpdatedFiles()) {
                String path = base.relativize(file.toPath()).toString().replace('\\', '/');
                (file.exists() ? present : gone).add(path);
            }
            if (present.isEmpty() && gone.isEmpty()) {
                return GitResult.failed(patch.getName() + " contains no changes that could be applied");
            }
            if (mode == com.jfx.git.model.PatchApplyMode.INDEX) {
                if (!present.isEmpty()) {
                    var add = git().add();
                    present.forEach(add::addFilepattern);
                    add.call();
                }
                if (!gone.isEmpty()) {
                    var remove = git().rm().setCached(true);
                    gone.forEach(remove::addFilepattern);
                    remove.call();
                }
            } else if (repository().resolve(Constants.HEAD) != null) {
                var reset = git().reset();
                present.forEach(reset::addPath);
                gone.forEach(reset::addPath);
                reset.call();
            }
            int files = present.size() + gone.size();
            return GitResult.ok("Applied " + patch.getName() + " to " + files
                    + (files == 1 ? " file" : " files"));
        } catch (Exception e) {
            return GitResult.failed("The patch does not apply: " + describe(e));
        }
    }

    public GitResult cherryPick(String commitHash) {
        return cherryPick(commitHash, 0);
    }

    /**
     * @param mainline 1-based parent a merge's changes are taken against; 0 picks parent 1
     *                 for a merge and nothing for an ordinary commit
     */
    public GitResult cherryPick(String commitHash, int mainline) {
        if (git() == null) return GitResult.failed("No repository open");
        return ctx.asGit(() -> cherryPickWithJGit(commitHash, mainline), null);
    }

    private GitResult cherryPickWithJGit(String commitHash, int mainline) {
        try {
            ObjectId id = repository().resolve(commitHash);
            if (id == null) return GitResult.failed("Unknown commit: " + commitHash);
            var command = git().cherryPick().include(id);
            int parent = mainlineFor(id, mainline);
            if (parent < 0) {
                return GitResult.failed("That commit has no parent " + mainline);
            }
            if (parent > 0) {
                command.setMainlineParentNumber(parent);
            }
            GitResult staged = refuseOverStagedChanges("cherry-pick");
            if (staged != null) return staged;
            var result = command.call();
            return switch (result.getStatus()) {
                case OK -> GitResult.ok("Cherry-picked " + shortId(commitHash));
                case CONFLICTING -> GitResult.conflict("Cherry-pick has conflicts", staging().getConflictingFiles());
                case FAILED -> result.getFailingPaths() == null
                        ? GitResult.failed("Cherry-pick failed: " + result.getStatus())
                        : refusedOver("cherry-pick", result.getFailingPaths());
            };
        } catch (Exception e) {
            return failure("Cherry-pick failed: ", e);
        }
    }

    /**
     * git's refusal to cherry-pick or revert over staged changes, or {@code null} when
     * the index matches HEAD.
     *
     * <p>git refuses whatever the staged paths are ("your local changes would be
     * overwritten by cherry-pick"), because the pick is committed from the index. JGit's
     * merger refuses a staged edit to a tracked file, with no word of which, but lets a
     * staged new file through: it went into the cherry-pick's commit - or, when the pick
     * stopped on a conflict, was deleted by Abort, as though the pick had added it.
     */
    private GitResult refuseOverStagedChanges(String what) throws Exception {
        if (repository().resolve(Constants.HEAD) == null) return null;
        List<String> staged = staging().stagedPaths();
        return staged.isEmpty() ? null : refusedOver(what, staged);
    }

    /**
     * The {@code -m} parent to use for {@code commitId}: {@code 0} for an ordinary commit,
     * the requested parent (or 1) for a merge, {@code -1} if the request is out of range.
     */
    private int mainlineFor(ObjectId commitId, int requested) throws IOException {
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit commit = walk.parseCommit(commitId);
            if (commit.getParentCount() <= 1) {
                return requested > 1 ? -1 : 0;
            }
            int parent = requested <= 0 ? 1 : requested;
            return parent <= commit.getParentCount() ? parent : -1;
        }
    }

    public GitResult revert(String commitHash) {
        return revert(commitHash, 0);
    }

    public GitResult revert(String commitHash, int mainline) {
        if (git() == null) return GitResult.failed("No repository open");
        return ctx.asGit(() -> revertWithJGit(commitHash, mainline), null);
    }

    private GitResult revertWithJGit(String commitHash, int mainline) {
        try {
            ObjectId id = repository().resolve(commitHash);
            if (id == null) return GitResult.failed("Unknown commit: " + commitHash);
            int parent = mainlineFor(id, mainline);
            if (parent < 0) {
                return GitResult.failed("That commit has no parent " + mainline);
            }
            GitResult staged = refuseOverStagedChanges("revert");
            if (staged != null) return staged;
            if (parent > 0) {
                return revertMerge(id, parent);
            }
            var cmd = git().revert().include(id);
            RevCommit reverted = cmd.call();
            if (reverted == null) {
                // JGit returns null when it stops early: on conflicts, or before starting
                // when the revert would overwrite local changes - which it reports only
                // through getFailingResult, and which is no conflict to resolve.
                MergeResult failing = cmd.getFailingResult();
                if (failing != null && failing.getFailingPaths() != null
                        && !failing.getFailingPaths().isEmpty()) {
                    return refusedOver("revert", failing.getFailingPaths());
                }
                List<String> conflicts = staging().getConflictingFiles();
                if (!conflicts.isEmpty()) {
                    return GitResult.conflict("Revert has conflicts", conflicts);
                }
                return GitResult.failed("Revert did not complete");
            }
            return GitResult.ok("Reverted " + shortId(commitHash));
        } catch (Exception e) {
            return failure("Revert failed: ", e);
        }
    }

    /**
     * {@code git revert -m <parent>} for a merge commit, which JGit's {@code RevertCommand}
     * refuses outright ("can only revert commits with one parent").
     *
     * <p>The same three-way merge RevertCommand does for an ordinary commit, with the
     * merge as the base and the chosen parent as "theirs": the result is HEAD minus what
     * the merge brought in relative to that parent. The message and the conflict state
     * ({@code REVERT_HEAD}, {@code MERGE_MSG}) are git's, so finishing it afterwards works
     * the same way as any other stopped revert.
     */
    private GitResult revertMerge(ObjectId mergeId, int parent) throws Exception {
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit merge = walk.parseCommit(mergeId);
            RevCommit mainline = walk.parseCommit(merge.getParent(parent - 1));
            ObjectId headId = repository().resolve(Constants.HEAD);
            if (headId == null) return GitResult.failed("There is no commit yet to revert onto");
            RevCommit head = walk.parseCommit(headId);

            String subject = merge.getShortMessage();
            String message = "Revert \"" + subject + "\"\n\n"
                    + "This reverts commit " + merge.getName() + ", reversing\n"
                    + "changes made to " + mainline.getName() + ".\n";

            var merger = (org.eclipse.jgit.merge.ResolveMerger)
                    MergeStrategy.RECURSIVE.newMerger(repository());
            merger.setWorkingTreeIterator(new FileTreeIterator(repository()));
            merger.setBase(merge.getTree());
            merger.setCommitNames(new String[] {"BASE", "HEAD", "parent of " + shortId(merge.getName())});
            if (merger.merge(head, mainline)) {
                if (head.getTree().getId().equals(merger.getResultTreeId())) {
                    return GitResult.failed("Nothing to revert - the merge's changes are already gone");
                }
                var checkout = new org.eclipse.jgit.dircache.DirCacheCheckout(repository(),
                        head.getTree(), repository().lockDirCache(), merger.getResultTreeId());
                checkout.setFailOnConflict(true);
                checkout.checkout();
                git().commit().setMessage(message).setReflogComment("revert: Revert \"" + subject + "\"").call();
                return GitResult.ok("Reverted merge " + shortId(merge.getName()));
            }
            if (merger.getFailingPaths() != null && !merger.getFailingPaths().isEmpty()) {
                return refusedOver("revert", merger.getFailingPaths());
            }
            repository().writeRevertHead(merge.getId());
            repository().writeMergeCommitMsg(new org.eclipse.jgit.merge.MergeMessageFormatter()
                    .formatWithConflicts(message, merger.getUnmergedPaths(), '#'));
            return GitResult.conflict("Revert has conflicts", staging().getConflictingFiles());
        }
    }

    public GitResult reset(String commitish, ResetMode mode) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            ResetCommand.ResetType jgitMode = switch (mode) {
                case SOFT  -> ResetCommand.ResetType.SOFT;
                case MIXED -> ResetCommand.ResetType.MIXED;
                case HARD  -> ResetCommand.ResetType.HARD;
            };
            git().reset().setMode(jgitMode).setRef(commitish).call();
            // A whole-tree reset abandons a pending squash in git (remove_branch_state);
            // JGit's reset leaves SQUASH_MSG to pre-fill some later, unrelated commit.
            SquashMessage.forget(repository().getDirectory());
            return GitResult.ok("Reset (" + mode.name().toLowerCase(java.util.Locale.ROOT)
                    + ") to " + shortId(commitish));
        } catch (Exception e) {
            return failure("Reset failed: ", e);
        }
    }

    /**
     * Abandons an in-progress merge the way {@code git merge --abort} does.
     *
     * <p>That is {@code reset --merge}, not {@code reset --hard}: only the paths the merge
     * itself changed go back to HEAD. This used to be a hard reset, which also threw away
     * every unrelated local edit the user had in the working tree when they started the
     * merge - edits the CLI engine keeps, and which no reflog can bring back.
     */
    public GitResult abortMerge() {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            if (repository().readMergeHeads() == null) {
                return GitResult.failed("There is no merge to abort");
            }
            resetMergeToHead();
            repository().writeMergeHeads(null);
            repository().writeMergeCommitMsg(null);
            new File(repository().getDirectory(), "MERGE_MODE").delete();
            return GitResult.ok("Merge aborted");
        } catch (Exception e) {
            return GitResult.failed("Could not abort merge: " + describe(e));
        }
    }

    /**
     * {@code git reset --merge HEAD}: every path where the index differs from HEAD - what
     * a merge, cherry-pick or revert staged or left conflicted - goes back to HEAD in both
     * the index and the working tree, and a path the operation added is removed.
     *
     * <p>Paths whose index still matches HEAD are not touched, so local changes the user
     * had before the operation began survive. That relies on the operation not having
     * started over staged changes - everything staged here is taken to be its output -
     * which git refuses and {@link #mergeWithJGit} and {@link #refuseOverStagedChanges}
     * refuse as well; JGit's own commands would have gone ahead.
     */
    private void resetMergeToHead() throws Exception {
        Status status = git().status().call();
        java.util.Set<String> touched = new java.util.TreeSet<>();
        touched.addAll(status.getAdded());
        touched.addAll(status.getChanged());
        touched.addAll(status.getRemoved());
        touched.addAll(status.getConflicting());
        if (touched.isEmpty()) return;

        ObjectId head = repository().resolve(Constants.HEAD);
        var reset = git().reset().setRef(Constants.HEAD);
        touched.forEach(reset::addPath);
        reset.call();

        List<String> inHead = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit headCommit = head == null ? null : walk.parseCommit(head);
            for (String path : touched) {
                boolean present = headCommit != null
                        && org.eclipse.jgit.treewalk.TreeWalk.forPath(
                                repository(), path, headCommit.getTree()) != null;
                if (present) {
                    inHead.add(path);
                } else {
                    File file = new File(repository().getWorkTree(), path);
                    if (file.isFile()) file.delete();
                }
            }
        }
        if (!inHead.isEmpty()) {
            var checkout = git().checkout().setForced(true);
            inHead.forEach(checkout::addPath);
            checkout.call();
        }
    }

    // ------------------------------------------------------------------
    // Whatever is in progress
    // ------------------------------------------------------------------

    public com.jfx.git.model.OperationState getOperationState() {
        if (repository() == null) return com.jfx.git.model.OperationState.none();
        return com.jfx.git.model.OperationState.detect(repository().getDirectory());
    }

    public GitResult continueOperation() {
        if (git() == null) return GitResult.failed("No repository open");
        var state = getOperationState();
        return switch (state.kind()) {
            case NONE -> GitResult.failed("Nothing is in progress");
            case MERGING -> finishWithPreparedMessage("merge");
            case CHERRY_PICKING -> sequencerOnly(state) != null ? sequencerOnly(state)
                    : finishWithPreparedMessage("cherry-pick");
            case REVERTING -> sequencerOnly(state) != null ? sequencerOnly(state)
                    : finishWithPreparedMessage("revert");
            case REBASING -> rebases().rebaseContinue();
            case REBASING_APPLY, APPLYING_MAILBOX -> needsTheCommandLine(state);
            case BISECTING -> GitResult.failed("A bisect continues by marking commits good or bad.");
        };
    }

    public GitResult skipOperation() {
        if (git() == null) return GitResult.failed("No repository open");
        var state = getOperationState();
        return switch (state.kind()) {
            case NONE -> GitResult.failed("Nothing is in progress");
            case MERGING -> GitResult.failed("A merge has no step to skip - finish it or abort it.");
            case CHERRY_PICKING, REVERTING -> {
                GitResult sequencer = sequencerOnly(state);
                if (sequencer != null) yield sequencer;
                // A single pick or revert: skipping it is abandoning it.
                GitResult abandoned = abandonPick();
                yield abandoned.isOk()
                        ? GitResult.ok("Skipped " + (state.stoppedAt() == null
                                ? "the stopped commit" : shorten(state.stoppedAt())))
                        : abandoned;
            }
            case REBASING -> rebases().runRebase(org.eclipse.jgit.api.RebaseCommand.Operation.SKIP, "Skipped");
            case REBASING_APPLY, APPLYING_MAILBOX -> needsTheCommandLine(state);
            case BISECTING -> {
                BisectStatus skipped = bisection().bisectSkip();
                yield GitResult.ok(skipped.message());
            }
        };
    }

    public GitResult abortOperation() {
        if (git() == null) return GitResult.failed("No repository open");
        var state = getOperationState();
        return switch (state.kind()) {
            case NONE -> GitResult.failed("Nothing is in progress");
            case MERGING -> abortMerge();
            case CHERRY_PICKING, REVERTING -> {
                GitResult sequencer = sequencerOnly(state);
                yield sequencer != null ? sequencer : abandonPick();
            }
            case REBASING -> rebases().rebaseAbort();
            case REBASING_APPLY, APPLYING_MAILBOX -> needsTheCommandLine(state);
            case BISECTING -> bisection().bisectReset();
        };
    }

    /**
     * Refuses a range cherry-pick or revert the command line started: its plan lives in
     * {@code .git/sequencer}, which JGit has no reader for, so carrying on here would
     * finish one step and silently forget the rest.
     */
    private GitResult sequencerOnly(com.jfx.git.model.OperationState state) {
        return new File(repository().getDirectory(), "sequencer").isDirectory()
                ? needsTheCommandLine(state)
                : null;
    }

    private static GitResult needsTheCommandLine(com.jfx.git.model.OperationState state) {
        return GitResult.failed(state.label() + " was started from the command line in a form"
                + " the JGit engine cannot continue. Switch to the Native Git engine in"
                + " Settings, or finish it in a terminal.");
    }

    /**
     * Commits a resolved merge, cherry-pick or revert with the message git prepared in
     * {@code MERGE_MSG}, as {@code git <op> --continue} does - through {@link #commit} so
     * the signing refusal, the hook guard and the original author all apply.
     */
    private GitResult finishWithPreparedMessage(String operation) {
        try {
            // Status read here, not through getConflictingFiles: that answers a failed
            // read with "no conflicts", which went on to commit the conflicted paths.
            Status status = git().status().call();
            if (!status.getConflicting().isEmpty()) {
                return GitResult.failed("Resolve and stage every conflicting file before"
                        + " continuing the " + operation + ".");
            }
            String message = com.jfx.git.model.PreparedMessage.clean(
                    repository().readMergeCommitMsg());
            if (message.isBlank()) {
                return GitResult.failed("There is no prepared message for this " + operation
                        + " - write one in the commit box and commit instead.");
            }
            if (!operation.equals("merge") && status.getAdded().isEmpty()
                    && status.getChanged().isEmpty() && status.getRemoved().isEmpty()) {
                return GitResult.failed("The " + operation + " is now empty - its changes are"
                        + " already here. Skip it, or abort.");
            }
            GitResult committed = staging().commit(message, false, false);
            return committed.isOk()
                    ? GitResult.ok("Finished the " + operation + " - " + committed.getMessage())
                    : committed;
        } catch (Exception e) {
            return GitResult.failed("Could not continue the " + operation + ": " + describe(e));
        }
    }

    /** Puts a single stopped cherry-pick or revert back to HEAD and forgets it. */
    private GitResult abandonPick() {
        try {
            resetMergeToHead();
            repository().writeCherryPickHead(null);
            repository().writeRevertHead(null);
            repository().writeMergeCommitMsg(null);
            return GitResult.ok("Aborted");
        } catch (Exception e) {
            return GitResult.failed("Could not abort: " + describe(e));
        }
    }
}
