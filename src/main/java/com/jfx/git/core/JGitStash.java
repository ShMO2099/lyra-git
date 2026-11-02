package com.jfx.git.core;

import com.jfx.git.model.StashOptions;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

/**
 * The stash: listing, stashing, applying, popping, dropping, and branching from one, for {@link GitService}.
 */
final class JGitStash extends JGitPart {

    JGitStash(JGitRepo ctx) {
        super(ctx);
    }

    // ------------------------------------------------------------------
    // Stash
    // ------------------------------------------------------------------

    /**
     * The stash as graph-placeable entries.
     *
     * <p>A stash commit's first parent is the commit it was taken from, which is where
     * the graph hangs it; the remaining parents are the index and untracked-file commits
     * git builds as part of stashing, and they are not points in the branch's history.
     */
    public List<com.jfx.git.model.StashEntry> getStashEntries() {
        if (git() == null) return List.of();
        try {
            List<com.jfx.git.model.StashEntry> entries = new ArrayList<>();
            int index = 0;
            for (RevCommit stash : git().stashList().call()) {
                var ident = stash.getAuthorIdent();
                String parent = stash.getParentCount() > 0
                        ? stash.getParent(0).getName()
                        : null;
                entries.add(new com.jfx.git.model.StashEntry(
                        index++,
                        stash.getName(),
                        parent,
                        stash.getFullMessage(),
                        ident != null ? ident.getName() : "",
                        ident != null ? ident.getWhenAsInstant() : java.time.Instant.EPOCH));
            }
            return entries;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read stash entries", e);
            return List.of();
        }
    }

    /**
     * Stashes everything, untracked files included - {@code git stash push -u}.
     *
     * <h2>One stash semantics for every engine</h2>
     * The CLI engine has always stashed with {@code -u}, and JGit without untracked files,
     * so "Stash" left a new file behind on one engine and took it away on the other. And
     * JGit's apply restored the index ({@code --index}) where the CLI's plain
     * {@code apply} does not. Both now do what the CLI does - include untracked, apply to
     * the working tree without restoring what was staged - and
     * {@code BackendConformanceTest} pins it.
     *
     * <h2>Keeping the index, and stashing some paths</h2>
     * JGit's {@code StashCreateCommand} has neither: it always stashes every path and
     * always hard-resets afterwards. Those two go through {@link #stashSelectively}, which
     * builds the same three commits git does and then clears only what git clears.
     */
    public GitResult stash(StashOptions options) {
        if (git() == null) return GitResult.failed("No repository open");
        return ctx.asGit(() -> stashWithJGit(options), null);
    }

    private GitResult stashWithJGit(StashOptions options) {
        StashOptions o = options == null ? StashOptions.everything(null) : options;
        String problem = o.problem();
        if (problem != null) return GitResult.failed(problem);
        try {
            // Every stash, not only partial and keep-index ones. JGit's StashCreateCommand
            // writes refs/stash without checking that the write landed and then hard-resets
            // the tree regardless: with the ref locked - another git process, a virus
            // scanner - the work was gone from the tree and reachable only through fsck, and
            // the stash reported success. stashSelectively records the stash first and
            // clears nothing unless that worked.
            return stashSelectively(o);
        } catch (Exception e) {
            return GitResult.failed("Stash failed: " + describe(e));
        }
    }

    /** git's words for a stash with nothing in it, used by every engine. */
    static final String NOTHING_TO_STASH = "No local changes to save";

    /** What a successful stash reports, on every engine. */
    static String stashedMessage(StashOptions o) {
        String what = o.isPartial()
                ? o.paths().size() == 1 ? o.paths().get(0) : o.paths().size() + " paths"
                : "changes";
        return o.hasMessage()
                ? "Stashed " + what + " as \"" + o.strippedMessage() + "\""
                : "Stashed " + what;
    }

    /**
     * {@code git stash push [--keep-index] [-u] [-- <paths>]}, built by hand.
     *
     * <p>The commits are git's: the index commit records the whole index, whatever the
     * paths; the untracked commit holds the untracked files under them (with {@code -u});
     * and the working-tree commit is the index with the working-tree state of the
     * selected paths laid over it. Then only those paths are cleared - to HEAD, or with
     * {@code --keep-index} to what is staged - and everything else is left alone, where
     * {@code StashCreateCommand} would have hard-reset the lot.
     *
     * <p>The working-tree commit is taken from the working tree even where it matches
     * HEAD but not the index (a staged change undone by hand). {@code StashCreateCommand}
     * skips that case and stores the staged content instead, so applying the stash would
     * bring back a change the user had already taken out.
     */
    private GitResult stashSelectively(StashOptions o) throws Exception {
        Ref head = repository().exactRef(Constants.HEAD);
        if (head == null || head.getObjectId() == null) {
            return GitResult.failed("You do not have the initial commit yet");
        }
        org.eclipse.jgit.treewalk.filter.TreeFilter scope = o.isPartial()
                ? org.eclipse.jgit.treewalk.filter.PathFilterGroup.createFromStrings(o.paths())
                : org.eclipse.jgit.treewalk.filter.TreeFilter.ALL;

        List<String> trackedInHead = new ArrayList<>();
        List<String> trackedNotInHead = new ArrayList<>();
        List<String> trackedInIndex = new ArrayList<>();
        List<String> trackedNotInIndex = new ArrayList<>();
        List<DirCacheEntry> untracked = new ArrayList<>();
        RevCommit headCommit;

        DirCache cache = repository().lockDirCache();
        try (ObjectInserter inserter = repository().newObjectInserter();
             RevWalk walk = new RevWalk(repository());
             org.eclipse.jgit.treewalk.TreeWalk treeWalk = new org.eclipse.jgit.treewalk.TreeWalk(repository())) {
            headCommit = walk.parseCommit(head.getObjectId());
            treeWalk.setRecursive(true);
            treeWalk.addTree(headCommit.getTree());
            treeWalk.addTree(new DirCacheIterator(cache));
            treeWalk.addTree(new FileTreeIterator(repository()));
            treeWalk.getTree(2, FileTreeIterator.class).setDirCacheIterator(treeWalk, 1);
            treeWalk.setFilter(org.eclipse.jgit.treewalk.filter.AndTreeFilter.create(List.of(
                    scope,
                    new org.eclipse.jgit.treewalk.filter.SkipWorkTreeFilter(1),
                    new org.eclipse.jgit.treewalk.filter.IndexDiffFilter(1, 2))));

            List<DirCacheEditor.PathEdit> wtEdits = new ArrayList<>();
            List<String> wtDeletes = new ArrayList<>();
            while (treeWalk.next()) {
                AbstractTreeIterator headIter = treeWalk.getTree(0, AbstractTreeIterator.class);
                DirCacheIterator indexIter = treeWalk.getTree(1, DirCacheIterator.class);
                org.eclipse.jgit.treewalk.WorkingTreeIterator wtIter =
                        treeWalk.getTree(2, org.eclipse.jgit.treewalk.WorkingTreeIterator.class);
                if (indexIter != null && !indexIter.getDirCacheEntry().isMerged()) {
                    return GitResult.failed("Cannot stash while " + treeWalk.getPathString()
                            + " has unresolved conflicts");
                }
                String path = treeWalk.getPathString();
                boolean tracked = headIter != null || indexIter != null;
                if (!tracked && (wtIter == null || !o.includeUntracked())) continue;
                if (tracked) {
                    (headIter != null ? trackedInHead : trackedNotInHead).add(path);
                    (indexIter != null ? trackedInIndex : trackedNotInIndex).add(path);
                }
                if (wtIter == null) {
                    // Deleted in the working tree: gone from the working-tree commit too.
                    if (indexIter != null) wtDeletes.add(path);
                    continue;
                }
                if (indexIter != null && wtIter.idEqual(indexIter)) continue;
                DirCacheEntry entry = new DirCacheEntry(treeWalk.getRawPath());
                entry.setLength(wtIter.getEntryLength());
                entry.setLastModified(wtIter.getEntryLastModifiedInstant());
                entry.setFileMode(wtIter.getEntryFileMode());
                try (java.io.InputStream in = wtIter.openEntryStream()) {
                    entry.setObjectId(inserter.insert(Constants.OBJ_BLOB,
                            wtIter.getEntryContentLength(), in));
                }
                if (tracked) {
                    wtEdits.add(new DirCacheEditor.PathEdit(entry) {
                        public void apply(DirCacheEntry ent) {
                            ent.copyMetaData(entry);
                        }
                    });
                } else {
                    untracked.add(entry);
                }
            }
            if (trackedInHead.isEmpty() && trackedNotInHead.isEmpty() && untracked.isEmpty()) {
                return GitResult.failed(NOTHING_TO_STASH);
            }

            String branch = Repository.shortenRefName(head.getTarget().getName());
            String onWhat = branch + ": " + headCommit.abbreviate(7).name() + " "
                    + headCommit.getShortMessage();
            PersonIdent ident = new PersonIdent(repository());
            org.eclipse.jgit.lib.CommitBuilder builder = new org.eclipse.jgit.lib.CommitBuilder();
            builder.setAuthor(ident);
            builder.setCommitter(ident);

            builder.setParentId(headCommit);
            builder.setTreeId(cache.writeTree(inserter));
            // No trailing newline on any of the three, as StashCreateCommand writes them:
            // the JGit engine reads the full message back as the stash's description.
            builder.setMessage("index on " + onWhat);
            ObjectId indexCommit = inserter.insert(builder);

            ObjectId untrackedCommit = null;
            if (!untracked.isEmpty()) {
                DirCache untrackedTree = DirCache.newInCore();
                org.eclipse.jgit.dircache.DirCacheBuilder untrackedBuilder = untrackedTree.builder();
                untracked.forEach(untrackedBuilder::add);
                untrackedBuilder.finish();
                builder.setParentIds(new ObjectId[0]);
                builder.setTreeId(untrackedTree.writeTree(inserter));
                builder.setMessage("untracked files on " + onWhat);
                untrackedCommit = inserter.insert(builder);
            }

            // Only in memory: the lock is released below without writing, so the real
            // index is untouched by building the working-tree commit's tree from it.
            if (!wtEdits.isEmpty() || !wtDeletes.isEmpty()) {
                DirCacheEditor editor = cache.editor();
                wtEdits.forEach(editor::add);
                wtDeletes.forEach(p -> editor.add(new DirCacheEditor.DeletePath(p)));
                editor.finish();
            }
            String stashMessage = o.hasMessage()
                    ? "On " + branch + ": " + o.strippedMessage()
                    : "WIP on " + onWhat;
            builder.setParentId(headCommit);
            builder.addParentId(indexCommit);
            if (untrackedCommit != null) builder.addParentId(untrackedCommit);
            builder.setTreeId(cache.writeTree(inserter));
            builder.setMessage(stashMessage);
            ObjectId stashCommit = inserter.insert(builder);
            inserter.flush();

            Ref current = repository().exactRef(Constants.R_STASH);
            RefUpdate update = repository().updateRef(Constants.R_STASH);
            update.setNewObjectId(stashCommit);
            update.setRefLogIdent(ident);
            update.setRefLogMessage(stashMessage, false);
            update.setForceRefLog(true);
            update.setExpectedOldObjectId(current != null ? current.getObjectId() : ObjectId.zeroId());
            // Checked before anything is cleared. The paths below are about to be reset
            // and their untracked files deleted, and the stash ref is the only way back to
            // them: a lock held by another git process or a virus scanner, or a stash made
            // elsewhere between the read above and this write, left the work recoverable
            // only through `git fsck` while this reported "Stashed".
            RefUpdate.Result written = update.forceUpdate();
            if (written != RefUpdate.Result.NEW && written != RefUpdate.Result.FORCED
                    && written != RefUpdate.Result.FAST_FORWARD) {
                return GitResult.failed("Could not record the stash (" + written
                        + "); nothing was changed in the working tree. Try again.");
            }
        } finally {
            cache.unlock();
        }

        clearStashedPaths(o.keepIndex(), headCommit, trackedInHead, trackedNotInHead,
                trackedInIndex, trackedNotInIndex, untracked);
        return GitResult.ok(stashedMessage(o));
    }

    /**
     * Takes what was just stashed out of the index and working tree - and nothing else.
     *
     * <p>Without {@code --keep-index} the stashed paths go back to HEAD, and a path HEAD
     * does not have (a newly added file) leaves both. With it the index stays as it is and
     * the working tree is brought to match it, which is git's
     * {@code checkout --no-overlay <index tree>}: a path staged for deletion goes from
     * the working tree as well.
     */
    private void clearStashedPaths(boolean keepIndex, RevCommit headCommit,
                                   List<String> inHead, List<String> notInHead,
                                   List<String> inIndex, List<String> notInIndex,
                                   List<DirCacheEntry> untracked) throws Exception {
        List<String> toDelete = new ArrayList<>();
        if (keepIndex) {
            if (!inIndex.isEmpty()) {
                git().checkout().addPaths(inIndex).call();
            }
            toDelete.addAll(notInIndex);
        } else {
            if (!inHead.isEmpty()) {
                git().checkout().setStartPoint(headCommit).addPaths(inHead).call();
            }
            if (!notInHead.isEmpty()) {
                DirCache index = repository().lockDirCache();
                try {
                    DirCacheEditor editor = index.editor();
                    notInHead.forEach(p -> editor.add(new DirCacheEditor.DeletePath(p)));
                    editor.commit();
                } finally {
                    index.unlock();
                }
            }
            toDelete.addAll(notInHead);
        }
        untracked.forEach(entry -> toDelete.add(entry.getPathString()));
        for (String path : toDelete) {
            Files.deleteIfExists(new File(repository().getWorkTree(), path).toPath());
        }
    }

    /**
     * Applies the stash at {@code index} and drops it.
     *
     * <p>The previous implementation only called {@code stashApply()}, which leaves the
     * entry in place - repeatedly hitting "Pop" silently re-applied the same stash.
     */
    public GitResult stashPop(int index) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            if (git().stashList().call().isEmpty()) {
                return GitResult.failed("No stash to pop");
            }
            applyStash(index);
            git().stashDrop().setStashRef(index).call();
            return GitResult.ok("Popped stash@{" + index + "}");
        } catch (org.eclipse.jgit.api.errors.StashApplyFailureException e) {
            return GitResult.conflict("Stash could not be applied cleanly", staging().getConflictingFiles());
        } catch (Exception e) {
            return failure("Stash pop failed: ", e);
        }
    }

    public GitResult stashApply(int index) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            applyStash(index);
            return GitResult.ok("Applied stash@{" + index + "}");
        } catch (org.eclipse.jgit.api.errors.StashApplyFailureException e) {
            return GitResult.conflict("Stash could not be applied cleanly", staging().getConflictingFiles());
        } catch (Exception e) {
            return failure("Stash apply failed: ", e);
        }
    }

    /**
     * {@code git stash apply}: the working-tree changes and untracked files come back,
     * the index is not restored - see {@link #stash(StashOptions)}.
     */
    private void applyStash(int index) throws Exception {
        Status before = git().status().call();
        java.util.Set<String> alreadyStaged = new java.util.HashSet<>();
        alreadyStaged.addAll(before.getAdded());
        alreadyStaged.addAll(before.getChanged());
        alreadyStaged.addAll(before.getRemoved());
        ObjectId stash = repository().resolve("stash@{" + index + "}");

        org.eclipse.jgit.api.errors.StashApplyFailureException conflicted = null;
        try {
            git().stashApply()
                    .setStashRef("stash@{" + index + "}")
                    .setRestoreIndex(false)
                    // Put back below, the way git does it.
                    .setRestoreUntracked(false)
                    .call();
        } catch (org.eclipse.jgit.api.errors.StashApplyFailureException e) {
            conflicted = e;
        }
        // git restores the untracked files even when the changes conflicted.
        List<String> inTheWay = restoreUntracked(stash);
        if (conflicted != null) throw conflicted;

        // Even with restoreIndex off, JGit's checkout of the merged tree writes that tree
        // into the index, so every applied change came back staged. git's apply unstages
        // them again - except a new file, which it leaves added so it is not lost among
        // the untracked ones - and keeps whatever the user had staged beforehand.
        Status after = git().status().call();
        java.util.Set<String> unstage = new java.util.TreeSet<>();
        unstage.addAll(after.getChanged());
        unstage.addAll(after.getRemoved());
        unstage.removeAll(alreadyStaged);
        if (!unstage.isEmpty()) {
            var reset = git().reset().setRef(Constants.HEAD);
            unstage.forEach(reset::addPath);
            reset.call();
        }
        if (!inTheWay.isEmpty()) {
            throw new org.eclipse.jgit.api.errors.StashApplyFailureException(
                    String.join(", ", inTheWay) + " already exist, so the stash's untracked files"
                            + " could not all be put back");
        }
    }

    /**
     * git's {@code restore_untracked}: each file in the stash's untracked-files commit is
     * checked out where the work tree has nothing at its path, and left out where it has
     * something - never overwritten.
     *
     * <p>Not JGit's own restore, which first merged that commit with HEAD and refused any
     * path HEAD has, whatever the work tree held. Once a merge or checkout brings in a file
     * with the name of a stashed untracked one, git puts the stashed one back as soon as
     * the place is free, and the file shows as a change to the tracked one; JGit never did,
     * and auto-stash ({@link AutoStash}) could not give the user their file back.
     *
     * @return the paths that were in the way, which the caller reports
     */
    private List<String> restoreUntracked(ObjectId stashId) throws java.io.IOException {
        List<String> inTheWay = new ArrayList<>();
        if (stashId == null) return inTheWay;
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit stash = walk.parseCommit(stashId);
            if (stash.getParentCount() < 3) return inTheWay;
            RevCommit untracked = walk.parseCommit(stash.getParent(2));
            var checkout = new org.eclipse.jgit.dircache.Checkout(repository());
            try (org.eclipse.jgit.treewalk.TreeWalk tree = new org.eclipse.jgit.treewalk.TreeWalk(repository())) {
                tree.addTree(untracked.getTree());
                // For the attributes the checkout reads - eol, filters - as JGit's own restore.
                tree.addTree(new FileTreeIterator(repository()));
                tree.setRecursive(true);
                // Only the stash's own paths: the rest of the work tree is not walked.
                tree.setFilter(new org.eclipse.jgit.treewalk.filter.TreeFilter() {
                    @Override
                    public boolean include(org.eclipse.jgit.treewalk.TreeWalk walker) {
                        return walker.getRawMode(0) != 0;
                    }

                    @Override
                    public boolean shouldBeRecursive() {
                        return false;
                    }

                    @Override
                    public org.eclipse.jgit.treewalk.filter.TreeFilter clone() {
                        return this;
                    }
                });
                var reader = tree.getObjectReader();
                while (tree.next()) {
                    String path = tree.getPathString();
                    File file = new File(repository().getWorkTree(), path);
                    if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        inTheWay.add(path);
                        continue;
                    }
                    DirCacheEntry entry = new DirCacheEntry(tree.getRawPath());
                    entry.setFileMode(tree.getFileMode(0));
                    entry.setObjectId(tree.getObjectId(0));
                    checkout.checkout(entry, new org.eclipse.jgit.dircache.DirCacheCheckout.CheckoutMetadata(
                            tree.getEolStreamType(org.eclipse.jgit.treewalk.TreeWalk.OperationType.CHECKOUT_OP),
                            tree.getFilterCommand(Constants.ATTR_FILTER_TYPE_SMUDGE)), reader, path);
                }
            }
        }
        return inTheWay;
    }

    public GitResult stashDrop(int index) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            git().stashDrop().setStashRef(index).call();
            return GitResult.ok("Dropped stash@{" + index + "}");
        } catch (Exception e) {
            return GitResult.failed("Stash drop failed: " + describe(e));
        }
    }

    /**
     * {@code git stash branch}: a branch at the stash's base, checked out, the stash
     * applied with its index ({@code apply --index}), then dropped.
     *
     * <p>Checked out before anything is applied and created in the same step, so a
     * checkout that local changes block leaves no branch behind - {@code checkout -b}
     * does not create one either when it cannot switch.
     */
    public GitResult stashBranch(String branchName, int index) {
        if (git() == null) return GitResult.failed("No repository open");
        String name = branchName == null ? "" : branchName.strip();
        if (name.isEmpty()) return GitResult.failed("A branch name is required");
        try {
            if (!Repository.isValidRefName(Constants.R_HEADS + name)) {
                return GitResult.failed("'" + name + "' is not a valid branch name");
            }
            if (repository().exactRef(Constants.R_HEADS + name) != null) {
                return GitResult.failed("A branch named '" + name + "' already exists");
            }
            List<RevCommit> stashes = new ArrayList<>(git().stashList().call());
            if (index < 0 || index >= stashes.size()) {
                return GitResult.failed("stash@{" + index + "} is not a valid stash");
            }
            RevCommit stash = stashes.get(index);
            if (stash.getParentCount() == 0) {
                return GitResult.failed("stash@{" + index + "} is not a stash commit");
            }
            try {
                git().checkout().setCreateBranch(true).setName(name)
                        .setStartPoint(stash.getParent(0).getName()).call();
            } catch (org.eclipse.jgit.api.errors.CheckoutConflictException e) {
                // CheckoutCommand creates the branch before it finds out it cannot switch.
                if (repository().exactRef(Constants.R_HEADS + name) != null
                        && !(Constants.R_HEADS + name).equals(repository().getFullBranch())) {
                    git().branchDelete().setBranchNames(name).setForce(true).call();
                }
                return GitResult.refused("Your local changes would be overwritten by checking out "
                        + name + ": " + String.join(", ", e.getConflictingPaths())
                        + ". Commit or stash them first.", localChangesIn(e.getConflictingPaths()));
            }
            try {
                git().stashApply()
                        .setStashRef(stash.getName())
                        .setRestoreIndex(true)
                        .setRestoreUntracked(true)
                        .call();
            } catch (org.eclipse.jgit.api.errors.StashApplyFailureException e) {
                return GitResult.conflict("Created " + name + ", but the stash did not apply"
                        + " cleanly and has been kept", staging().getConflictingFiles());
            }
            git().stashDrop().setStashRef(index).call();
            return GitResult.ok("Created " + name + " from the stash and dropped it");
        } catch (Exception e) {
            return GitResult.failed("Could not create a branch from the stash: " + describe(e));
        }
    }
}
