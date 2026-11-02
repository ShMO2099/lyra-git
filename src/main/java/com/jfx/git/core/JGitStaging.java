package com.jfx.git.core;

import com.jfx.git.model.StagingState;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.hooks.CommitMsgHook;
import org.eclipse.jgit.hooks.PreCommitHook;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Status, staging, discarding, committing, and the hunk-level edits to the index and
 * the working tree, for {@link GitService}.
 */
final class JGitStaging extends JGitPart {

    JGitStaging(JGitRepo ctx) {
        super(ctx);
    }

    // ------------------------------------------------------------------
    // Status / staging
    // ------------------------------------------------------------------

    /**
     * Whether a merge, rebase, cherry-pick or revert is waiting to be finished.
     *
     * <p>Not simply {@code != SAFE}: JGit reports {@link RepositoryState#BISECTING}
     * whenever {@code BISECT_LOG} exists, and a bisect has nothing to commit. Counting it
     * made every bisect started on the command line look like uncommitted work - the WIP
     * row appeared, and the dirty-tree guards on rebase and undo refused.
     */
    boolean isMidOperation() {
        RepositoryState state = repository().getRepositoryState();
        return state != RepositoryState.SAFE && state != RepositoryState.BISECTING;
    }

    /**
     * Whether there is work only a commit can finish - a dirty tree, or a merge (or
     * cherry-pick, or revert) sitting resolved and waiting to be completed.
     *
     * <h2>Why {@code Status.isClean()} alone is not enough</h2>
     * A conflict resolved by choosing one side verbatim - "Take ours" is the obvious
     * case, but an ordinary hand-edit that happens to reproduce HEAD's content hits the
     * same path - leaves the working tree, the index and HEAD in perfect agreement.
     * {@code isClean()} is then {@code true}: nothing is unstaged, nothing is staged,
     * nothing conflicts. But the merge itself is not done. {@code MERGE_HEAD} is still on
     * disk, and completing it still needs a commit with two parents - real {@code git}
     * reports exactly this as "All conflicts fixed but you are still merging."
     *
     * <p>Reading {@code isClean()} alone made the WIP row - the only place this
     * application offers to make that commit - vanish from the graph the moment such a
     * conflict was resolved, with no path back to finishing the merge. This also reads
     * {@link RepositoryState}, which JGit keeps precisely for "is an operation still in
     * progress" and which a byte-for-byte-clean tree does not affect.
     *
     * @throws GitOperationException if status cannot be read - previously this returned
     *         {@code false} on error, silently reporting a dirty tree as clean.
     */
    public boolean hasUncommittedChanges() {
        if (git() == null) return false;
        try {
            boolean midOperation = isMidOperation();
            Status status = git().status().call();
            return midOperation || hasReportedChanges(status, uninitializedSubmodulePaths());
        } catch (Exception e) {
            throw new GitOperationException("Could not read repository status: " + describe(e), e);
        }
    }

    /**
     * Git treats a registered but deinitialised submodule as clean. JGit's working-tree
     * status can report the empty directory left by {@code submodule deinit} as a change
     * to its gitlink, so exclude only entries JGit identifies as uninitialised. A
     * checked-out submodule with a different revision remains a real change.
     */
    private Set<String> uninitializedSubmodulePaths() {
        try {
            Set<String> paths = new HashSet<>();
            git().submoduleStatus().call().forEach((path, status) -> {
                if (status.getType() == org.eclipse.jgit.submodule.SubmoduleStatusType.UNINITIALIZED) {
                    paths.add(path);
                }
            });
            return paths;
        } catch (Exception e) {
            // Failure to classify a submodule must not hide a reported change.
            LOGGER.log(Level.FINE, "Could not identify uninitialised submodules", e);
            return Set.of();
        }
    }

    private static boolean hasReportedChanges(Status status, Set<String> ignoredPaths) {
        // Staged gitlink changes remain real changes even if that submodule is
        // uninitialised; only the working-tree-side signal can be JGit's false positive.
        return hasReportedChanges(status.getAdded(), Set.of())
                || hasReportedChanges(status.getChanged(), Set.of())
                || hasReportedChanges(status.getRemoved(), Set.of())
                || hasReportedChanges(status.getConflicting(), Set.of())
                || hasReportedChanges(status.getMissing(), ignoredPaths)
                || hasReportedChanges(status.getModified(), ignoredPaths)
                || hasReportedChanges(status.getUntracked(), ignoredPaths);
    }

    private static boolean hasReportedChanges(Collection<String> paths, Set<String> ignoredPaths) {
        return paths.stream().anyMatch(path -> !isInsideIgnoredSubmodule(path, ignoredPaths));
    }

    private static List<String> excludingPaths(Collection<String> paths, Set<String> ignoredPaths) {
        return paths.stream().filter(path -> !isInsideIgnoredSubmodule(path, ignoredPaths)).toList();
    }

    private static boolean isInsideIgnoredSubmodule(String path, Set<String> ignoredPaths) {
        return ignoredPaths.stream().anyMatch(module ->
                path.equals(module) || path.startsWith(module + "/"));
    }

    public List<String> getStagedFiles() {
        if (git() == null) return List.of();
        try {
            return stagedPaths();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list staged files", e);
            return List.of();
        }
    }

    /**
     * The paths whose index entry differs from HEAD, for the decisions that cannot treat
     * "status could not be read" as "nothing staged".
     *
     * <p>{@link #getStagedFiles} answers a failed status with an empty list, which is
     * right for drawing a panel and wrong for a guard: {@code commit} said "Nothing staged
     * to commit" over a locked or corrupt index, sending the user looking for a problem
     * with their staging rather than with the repository.
     */
    List<String> stagedPaths() throws org.eclipse.jgit.api.errors.GitAPIException {
        Status status = git().status().call();
        java.util.Set<String> files = new java.util.TreeSet<>();
        files.addAll(status.getAdded());
        files.addAll(status.getChanged());
        files.addAll(status.getRemoved());
        return List.copyOf(files);
    }

    public List<String> getConflictingFiles() {
        if (git() == null) return List.of();
        try {
            return List.copyOf(git().status().call().getConflicting());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list conflicts", e);
            return List.of();
        }
    }

    /**
     * How each changed path changed, as a single letter per path.
     *
     * <h2>Why this was missing, and what it cost</h2>
     * {@link StagingState#statusOf} falls back to {@code M} for any path it has no letter
     * for, and this engine was handing it an empty map - so every file in the staging
     * lists read as a modification, a brand-new file included. The fallback exists for an
     * engine that cannot answer; the default engine simply never did.
     *
     * <p>One letter per path, with the staged side winning where both apply, matching
     * {@code NativeGitBackend.letterOf} exactly - the two engines have to agree, or the
     * same repository reads differently depending on a setting the user did not know they
     * had. An untracked file reads {@code A}: it is not in the index yet, but that is
     * what it is about to become, and what git itself shows.
     *
     * <p>Renames are not detected. JGit's status reports one as a delete beside an add,
     * and inventing an {@code R} here without the similarity pass that justifies it would
     * be a guess wearing the same letter as the CLI engine's fact.
     */
    private static java.util.Map<String, Character> statusLetters(Status status) {
        java.util.Map<String, Character> letters = new java.util.HashMap<>();
        for (String path : status.getUntracked()) {
            letters.put(path, 'A');
        }
        for (String path : status.getModified()) {
            letters.put(path, 'M');
        }
        for (String path : status.getMissing()) {
            letters.put(path, 'D');
        }
        // Staged last, so it wins wherever a path appears on both sides.
        for (String path : status.getAdded()) {
            letters.put(path, 'A');
        }
        for (String path : status.getChanged()) {
            letters.put(path, 'M');
        }
        for (String path : status.getRemoved()) {
            letters.put(path, 'D');
        }
        return letters;
    }

    /**
     * Staged renames, as new path to old path.
     *
     * <h2>Why this is a second pass rather than part of the status</h2>
     * JGit's {@code Status} has no similarity detection at all: {@code git mv a b} reaches
     * it as {@code b} added and {@code a} removed, two unrelated facts. Recovering the one
     * fact behind them needs an actual diff with rename detection turned on, which
     * {@code Status} does not run and cannot be asked to.
     *
     * <h2>HEAD against the index, and nothing else</h2>
     * Only the staged side is worth asking about, and this is also the only side that can
     * be asked cheaply. A working-tree rename is a file missing from disk beside an
     * untracked file that happens to hold its contents - git itself does not pair those,
     * and neither do the other two engines, so pairing them here would make this engine
     * the odd one out in the opposite direction. Scanning the index also avoids walking
     * the working tree, so no ignored build output is visited.
     *
     * <h2>Skipped unless a rename is possible</h2>
     * The similarity pass compares blob content, and this runs on every status refresh.
     * A rename needs both an add and a delete staged, so the ordinary case - editing files
     * and staging them - returns before touching the repository at all.
     */
    private java.util.Map<String, String> detectStagedRenames(Status status) {
        if (status.getAdded().isEmpty() || status.getRemoved().isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> renames = new java.util.HashMap<>();
        try (ObjectReader reader = repository().newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repository());
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            ObjectId head = repository().resolve("HEAD^{tree}");
            AbstractTreeIterator oldTree = head == null
                    ? new EmptyTreeIterator()
                    : new CanonicalTreeParser(null, reader, head);

            for (DiffEntry entry : df.scan(oldTree,
                    new DirCacheIterator(repository().readDirCache()))) {
                if (entry.getChangeType() == DiffEntry.ChangeType.RENAME) {
                    renames.put(entry.getNewPath(), entry.getOldPath());
                }
            }
        } catch (Exception e) {
            // A rename that goes undetected shows as the add and delete it already showed,
            // which is worse than the truth but is not wrong about anything.
            LOGGER.log(Level.WARNING, "Could not detect staged renames", e);
        }
        return renames;
    }

    public StagingState getStagingState() {
        if (git() == null) return StagingState.empty();
        try {
            Status status = git().status().call();
            Set<String> uninitialized = uninitializedSubmodulePaths();

            List<String> unstaged = new ArrayList<>();
            unstaged.addAll(excludingPaths(status.getModified(), uninitialized));
            unstaged.addAll(excludingPaths(status.getMissing(), uninitialized));
            unstaged.addAll(excludingPaths(status.getUntracked(), uninitialized));
            unstaged.addAll(status.getConflicting());

            List<String> staged = new ArrayList<>();
            staged.addAll(status.getAdded());
            staged.addAll(status.getChanged());
            staged.addAll(status.getRemoved());

            java.util.Map<String, Character> letters = statusLetters(status);
            Set<String> onlyUnstagedUninitialized = new HashSet<>(uninitialized);
            onlyUnstagedUninitialized.removeAll(staged);
            Set<String> conflicting = new HashSet<>(status.getConflicting());
            letters.keySet().removeIf(path -> !staged.contains(path)
                    && !conflicting.contains(path)
                    && isInsideIgnoredSubmodule(path, onlyUnstagedUninitialized));

            // A rename reaches JGit's status as an unrelated delete beside an unrelated
            // add, because Status does no similarity pass. Folding the pair back into one
            // row is what the other two engines already report, and two rows for one move
            // is not merely uglier - it invites unstaging half of a rename.
            for (var rename : detectStagedRenames(status).entrySet()) {
                String newPath = rename.getKey();
                String oldPath = rename.getValue();
                letters.put(newPath, 'R');
                staged.remove(oldPath);
                letters.remove(oldPath);
            }

            // Both come from the repository object that is already open, and both save
            // the staging panel a synchronous call on the FX thread - see StagingState.
            return new StagingState(
                    unstaged.stream().distinct().sorted().toList(),
                    staged.stream().distinct().sorted().toList(),
                    status.getConflicting(),
                    letters,
                    branches().getCurrentBranch(),
                    isMidOperation());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read staging state", e);
            return StagingState.empty();
        }
    }

    /**
     * Stages one path; handles deletions as well as additions.
     *
     * <p>Whether the path is still there is asked without following links. A symlink
     * whose target is gone is still a file git tracks, but {@code File.exists()} follows
     * it, answered "no", and the path went to {@code git rm} - which, not told
     * {@code --cached}, deleted the link from the working tree. And a deletion is only
     * ever staged here, never made: {@code rm} is always {@code --cached}.
     */
    public GitResult stageFile(String path) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            java.nio.file.Path onDisk = repository().getWorkTree().toPath().resolve(path);
            if (Files.exists(onDisk, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                git().add().addFilepattern(path).call();
            } else {
                git().rm().addFilepattern(path).setCached(true).call();
            }
            return GitResult.ok("Staged " + path);
        } catch (Exception e) {
            return GitResult.failed("Could not stage " + path + ": " + describe(e));
        }
    }

    public GitResult stageAll() {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            // setUpdate(false) picks up new files; a second pass records deletions.
            git().add().addFilepattern(".").call();
            git().add().addFilepattern(".").setUpdate(true).call();
            return GitResult.ok("Staged all changes");
        } catch (Exception e) {
            return GitResult.failed("Could not stage all: " + describe(e));
        }
    }

    /** Removes a path from the index, keeping the working-tree copy. */
    public GitResult unstageFile(String path) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            if (repository().resolve(Constants.HEAD) == null) {
                // No commit yet: reset has no ref to fall back to, so drop from the index.
                git().rm().addFilepattern(path).setCached(true).call();
            } else {
                git().reset().addPath(path).call();
            }
            return GitResult.ok("Unstaged " + path);
        } catch (Exception e) {
            return GitResult.failed("Could not unstage " + path + ": " + describe(e));
        }
    }

    public GitResult unstageAll() {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            if (repository().resolve(Constants.HEAD) == null) {
                List<String> staged = stagedPaths();
                if (!staged.isEmpty()) {
                    var rm = git().rm().setCached(true);
                    staged.forEach(rm::addFilepattern);
                    rm.call();
                }
            } else {
                git().reset().call();
            }
            return GitResult.ok("Unstaged all changes");
        } catch (Exception e) {
            return GitResult.failed("Could not unstage all: " + describe(e));
        }
    }

    /** Throws away working-tree changes for a path; deletes the file if it is untracked. */
    public GitResult discardChanges(String path) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            boolean untracked = git().status().call().getUntracked().contains(path);
            if (untracked) {
                File f = new File(repository().getWorkTree(), path);
                if (f.exists() && !f.delete()) {
                    return GitResult.failed("Could not delete " + path);
                }
            } else {
                git().checkout().addPath(path).call();
            }
            return GitResult.ok("Discarded changes in " + path);
        } catch (Exception e) {
            return GitResult.failed("Could not discard " + path + ": " + describe(e));
        }
    }

    /**
     * Writes {@code filePath} as it exists in {@code commitHash} over the working tree
     * copy — restoring a file deleted since, or rolling back edits to an older state.
     * The file is left unstaged, so what happened stays visible in the diff.
     */
    public GitResult restoreFileFromCommit(String commitHash, String filePath) {
        return restoreFileFromCommit(commitHash, filePath, filePath);
    }

    /**
     * {@link #restoreFileFromCommit(String, String)}, written into {@code intoPath}: the
     * file as it is called now, when it has been renamed since that commit.
     */
    public GitResult restoreFileFromCommit(String commitHash, String filePath, String intoPath) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            // getFileBytesAtCommit answers an empty array for a missing path (its
            // long-standing contract), and an empty blob is legal — so ask the tree
            // directly whether the file was there at all.
            if (!hasFileAtCommit(commitHash, filePath)) {
                return GitResult.failed(filePath + " does not exist in " + commitHash);
            }
            byte[] blob = diffs().getFileBytesAtCommit(commitHash, filePath);
            java.nio.file.Path target;
            try {
                // Through the shared policy, as writeWorkingTreeFile and the CLI engine's
                // restore do: new File(workTree, path) followed a link at the destination
                // and wrote the commit's bytes to wherever it pointed. See WorktreePaths.
                target = WorktreePaths.resolveForWrite(repository().getWorkTree(), intoPath);
            } catch (IllegalArgumentException e) {
                return GitResult.failed("Could not restore " + intoPath + ": " + e.getMessage());
            }
            java.nio.file.Path parent = target.getParent();
            if (parent != null) java.nio.file.Files.createDirectories(parent);
            java.nio.file.Files.write(target, blob);
            return GitResult.ok(restoredMessage(commitHash, filePath, intoPath));
        } catch (Exception e) {
            return GitResult.failed("Could not restore " + intoPath + ": " + describe(e));
        }
    }

    /** What a restore reports, naming the commit's name for the file when it was another. */
    private static String restoredMessage(String commitHash, String filePath, String intoPath) {
        String shortHash = commitHash.length() >= 7 ? commitHash.substring(0, 7) : commitHash;
        return intoPath.equals(filePath)
                ? "Restored " + filePath + " from " + shortHash
                : "Restored " + intoPath + " from " + shortHash + " (" + filePath + " there)";
    }

    private boolean hasFileAtCommit(String commitHash, String filePath) throws Exception {
        try (RevWalk walk = new RevWalk(repository())) {
            org.eclipse.jgit.lib.ObjectId id = repository().resolve(commitHash);
            if (id == null) return false;
            RevCommit parsed = walk.parseCommit(id);
            try (org.eclipse.jgit.treewalk.TreeWalk tree =
                         org.eclipse.jgit.treewalk.TreeWalk.forPath(repository(), filePath, parsed.getTree())) {
                return tree != null;
            }
        }
    }

    /** Restores every changed tracked file and deletes every untracked one, in one sweep. */
    public GitResult discardAllChanges() {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            var status = git().status().call();
            Set<String> uninitialized = uninitializedSubmodulePaths();
            // Deleted files must be restored too, which checkout of the remaining
            // paths does; getMissing is how JGit spells "deleted from the work tree".
            java.util.Set<String> toRestore = new java.util.TreeSet<>();
            toRestore.addAll(excludingPaths(status.getModified(), uninitialized));
            toRestore.addAll(excludingPaths(status.getMissing(), uninitialized));
            // One checkout for all of them: a checkout per path rewrote the whole index
            // once per path, which on a large index made "Discard all" quadratic.
            if (!toRestore.isEmpty()) {
                git().checkout().addPaths(List.copyOf(toRestore)).call();
            }
            int removed = git().clean().setCleanDirectories(true).call().size();
            return GitResult.ok("Discarded changes in " + (toRestore.size() + removed) + " path(s)");
        } catch (Exception e) {
            return GitResult.failed("Could not discard all changes: " + describe(e));
        }
    }

    /**
     * Commits the staged changes.
     *
     * @param amend replace the previous commit instead of adding a new one
     */
    public GitResult commit(String message, boolean amend) {
        return commit(message, amend, false);
    }

    /** @param skipHooks bypass {@code pre-commit}/{@code commit-msg} - {@code git commit --no-verify} */
    public GitResult commit(String message, boolean amend, boolean skipHooks) {
        if (git() == null) return GitResult.failed("No repository open");
        if (message == null || message.isBlank()) {
            return GitResult.failed("A commit message is required");
        }
        // Identity, signing, message cleanup and encoding, read the way git reads them -
        // includeIf and all - rather than the way JGit does. commit.gpgsign is refused
        // here because committing anyway would silently produce an unsigned commit under
        // a setting that claims otherwise; a missing identity is refused because JGit
        // would otherwise invent <osuser>@<hostname> where git stops. See CommitPreflight.
        CommitPreflight.Outcome preflight = CommitPreflight.evaluate(repository());
        if (preflight.refusal() != null) {
            return GitResult.failed(preflight.refusal());
        }
        CommitPreflight.Plan plan = preflight.plan();
        String cleaned = org.eclipse.jgit.lib.CommitConfig.cleanText(message, plan.cleanup(),
                plan.commentChar() == null ? (char) 0 : plan.commentChar());
        if (cleaned.isBlank()) {
            // What git says when commit.cleanup=strip leaves nothing but comments behind.
            return GitResult.failed("Aborting commit due to empty commit message.");
        }
        // prepare-commit-msg is not one of the hooks JGit knows about at all, so it never
        // runs here while git runs it on every commit - including --no-verify ones. Ticking
        // "Skip hooks" is the user saying, deliberately, that hooks should not run.
        if (!skipHooks && hasRunnableHook(PREPARE_COMMIT_MSG)) {
            return GitResult.failed("This repository has a " + PREPARE_COMMIT_MSG + " hook, which "
                    + "the JGit engine cannot run, so committing here would skip it without saying "
                    + "so. Switch to the Native Git engine in Settings to commit with it running, "
                    + "or tick \"Skip hooks\" to bypass it deliberately.");
        }
        // JGit does not run the hooks itself; it asks the platform FS to, and a plain
        // FS_Win32 - a Windows machine with no sh on PATH - answers NOT_SUPPORTED and
        // CommitCommand carries on regardless. The commit then succeeds with the hook
        // never having run, which is the single outcome a blocking pre-commit hook exists
        // to prevent, and nothing anywhere says so. Refuse instead.
        if (!skipHooks) {
            String unrunnable = blockingHookThisEngineCannotRun();
            if (unrunnable != null) {
                return GitResult.failed("This repository has a " + unrunnable + " hook, but the "
                        + "JGit engine cannot run hooks on this system, so committing here would "
                        + "skip it without saying so. Switch to the Native Git engine in "
                        + "Settings to commit with hooks running, or tick \"Skip hooks\" to "
                        + "bypass it deliberately.");
            }
        }
        boolean midOperation = isMidOperation();
        try {
            // A merge resolved by taking one side verbatim leaves the index
            // byte-identical to HEAD - nothing "staged" by a content diff - but the
            // commit is still what finishes it, recording the second parent. Git allows
            // exactly that one kind of empty commit and refuses the rest, including a
            // cherry-pick or revert that conflict resolution emptied.
            boolean concludingMerge = repository().readMergeHeads() != null;
            // stagedPaths, not getStagedFiles: a status that cannot be read is an error to
            // report, not an empty index.
            if (!amend && !midOperation && stagedPaths().isEmpty()) {
                return GitResult.failed("Nothing staged to commit");
            }
            if (amend && amendWouldBeEmpty()) {
                return GitResult.failed("Amending would make the last commit empty: the staged "
                        + "tree is identical to its parent's. Undo the commit instead, or stage "
                        + "a change to amend it with.");
            }
            var command = git().commit()
                    .setMessage(message)
                    .setAmend(amend)
                    .setNoVerify(skipHooks)
                    // JGit's own emptiness check compares with HEAD even when amending, which
                    // would refuse a message-only amend; amendWouldBeEmpty above is git's
                    // rule (compare with HEAD's parent) instead.
                    .setAllowEmpty(amend || concludingMerge)
                    // Signing was decided above; JGit must not re-read a gpgsign that git's
                    // reading has already overruled.
                    .setSign(Boolean.FALSE)
                    .setCommitter(plan.committer())
                    .setCleanupMode(plan.cleanup());
            if (plan.commentChar() != null) {
                command.setCommentCharacter(plan.commentChar());
            }
            PersonIdent author = authorFor(amend, plan);
            if (author != null) {
                command.setAuthor(author);
            }
            RevCommit created = command.call();
            // git commit deletes SQUASH_MSG; JGit only does when it read the message
            // from there, and this always passes one. See SquashMessage.forget.
            SquashMessage.forget(repository().getDirectory());
            return GitResult.ok("Committed " + created.getName().substring(0, 7));
        } catch (org.eclipse.jgit.api.errors.EmptyCommitException e) {
            return GitResult.failed(midOperation
                    ? "The resolved changes are identical to HEAD, so this commit would be empty. "
                      + "Skip this commit or abort the operation instead."
                    : "Nothing to commit: the staged changes leave the tree exactly as HEAD has it");
        } catch (Exception e) {
            return GitResult.failed("Commit failed: " + describe(e));
        }
    }

    private static final String PREPARE_COMMIT_MSG = "prepare-commit-msg";

    /**
     * Who the new commit is by.
     *
     * <p>An amend keeps the original author, as {@code git commit --amend} does - JGit
     * already does that when no author is set. A resolved cherry-pick keeps the picked
     * commit's author, as git does and JGit, which sets author = committer in that
     * state, does not; so does a stopped rebase step, from its author script - see
     * {@link #authorOfStoppedCommit()}.
     *
     * @return {@code null} to leave JGit's default in place
     */
    private PersonIdent authorFor(boolean amend, CommitPreflight.Plan plan) {
        if (amend) return null;
        PersonIdent original = authorOfStoppedCommit();
        return original != null ? original : plan.author();
    }

    /**
     * Git's rule for an empty amend: the staged tree matches what HEAD's parent had, so
     * the amended commit would change nothing. A merge commit is exempt - it records the
     * joining of two lines even when it changes no files.
     */
    private boolean amendWouldBeEmpty() throws IOException {
        ObjectId headId = repository().resolve(Constants.HEAD + "^{commit}");
        if (headId == null) return false;
        try (RevWalk walk = new RevWalk(repository());
             org.eclipse.jgit.treewalk.TreeWalk tree = new org.eclipse.jgit.treewalk.TreeWalk(repository())) {
            RevCommit head = walk.parseCommit(headId);
            if (head.getParentCount() > 1) return false;
            tree.setRecursive(true);
            if (head.getParentCount() == 1) {
                tree.addTree(walk.parseCommit(head.getParent(0).getId()).getTree());
            } else {
                tree.addTree(new EmptyTreeIterator());
            }
            tree.addTree(new DirCacheIterator(repository().readDirCache()));
            tree.setFilter(org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF);
            return !tree.next();
        }
    }

    /**
     * Whether git would run hook {@code name} here: present in the hooks directory git
     * would use, not a sample, and executable where the filesystem can say.
     */
    private boolean hasRunnableHook(String name) {
        File hook = new File(ctx.hooksDirectory(), name);
        if (!hook.isFile()) return false;
        FS fs = repository().getFS();
        return fs == null || !fs.supportsExecute() || fs.canExecute(hook);
    }

    /**
     * The author of the commit a stopped cherry-pick or rebase step was replaying, or
     * {@code null} when nothing is being replayed.
     *
     * <p>Finishing a conflicted cherry-pick is still that commit, only resolved, and git
     * keeps its author: {@code git commit} reads {@code CHERRY_PICK_HEAD}, and a rebase
     * step reads {@code rebase-merge/author-script}. JGit's {@code CommitCommand} does
     * neither - in {@code CHERRY_PICKING_RESOLVED} it makes the committer the author - so
     * the same resolution credited the original author on the CLI engine and the person
     * resolving the conflict on this one.
     *
     * <p>A rebase paused at an {@code edit} step is left alone: new commits made there are
     * the user's own, and amending keeps the author anyway.
     */
    private PersonIdent authorOfStoppedCommit() {
        try {
            ObjectId cherryPickHead = repository().readCherryPickHead();
            if (cherryPickHead != null) {
                try (RevWalk walk = new RevWalk(repository())) {
                    return walk.parseCommit(cherryPickHead).getAuthorIdent();
                }
            }
            File rebaseMerge = new File(repository().getDirectory(), "rebase-merge");
            File authorScript = new File(rebaseMerge, "author-script");
            if (authorScript.isFile() && !new File(rebaseMerge, "amend").exists()) {
                return parseAuthorScript(java.nio.file.Files.readString(
                        authorScript.toPath(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read the stopped commit's author", e);
        }
        return null;
    }

    /**
     * Reads the {@code GIT_AUTHOR_NAME='…'} lines git and JGit write for a stopped step.
     *
     * <p>The date is {@code @<seconds> <±hhmm>} or git's own raw form; anything this does
     * not understand keeps the name and email with the current time rather than dropping
     * the author altogether.
     */
    static PersonIdent parseAuthorScript(String script) {
        String name = null;
        String email = null;
        String date = null;
        for (String line : script.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            // Shell-quoted: '…' with an embedded quote written as '\''.
            if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1).replace("'\\''", "'");
            }
            switch (key) {
                case "GIT_AUTHOR_NAME" -> name = value;
                case "GIT_AUTHOR_EMAIL" -> email = value;
                case "GIT_AUTHOR_DATE" -> date = value;
                default -> { }
            }
        }
        if (name == null || email == null) return null;
        if (date != null) {
            String raw = date.startsWith("@") ? date.substring(1) : date;
            String[] parts = raw.trim().split("\\s+");
            try {
                long seconds = Long.parseLong(parts[0]);
                java.time.ZoneOffset zone = java.time.ZoneOffset.UTC;
                if (parts.length > 1 && parts[1].matches("[+-]\\d{4}")) {
                    int sign = parts[1].charAt(0) == '-' ? -1 : 1;
                    int hours = Integer.parseInt(parts[1].substring(1, 3));
                    int minutes = Integer.parseInt(parts[1].substring(3, 5));
                    zone = java.time.ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60));
                }
                return new PersonIdent(name, email, java.time.Instant.ofEpochSecond(seconds), zone);
            } catch (RuntimeException e) {
                // Fall through: the identity is what matters most.
            }
        }
        return new PersonIdent(name, email);
    }

    /**
     * The name of a commit-blocking hook this engine would skip silently, or {@code null}
     * when there is nothing to worry about.
     *
     * <p>Two questions, and both have to be asked. Whether a hook is installed comes from
     * {@link FS#findHook}, which honours {@code core.hooksPath} - a shared team hooks
     * directory is exactly the case where the user does not know what is meant to run.
     * Whether it could run comes from the probe above.
     *
     * <p>{@code FS.supportsExecute()} looks like the same question and is not:
     * {@code FS_Win32_Cygwin} reports {@code false} for it while running hooks perfectly
     * well, so keying off it would refuse commits on machines where nothing is wrong.
     */
    private String blockingHookThisEngineCannotRun() {
        try {
            FS fs = repository().getFS();
            if (fs == null) return null;

            String installed = null;
            for (String hook : List.of(PreCommitHook.NAME, CommitMsgHook.NAME)) {
                if (fs.findHook(repository(), hook) != null || hasRunnableHook(hook)) {
                    installed = hook;
                    break;
                }
            }
            if (installed == null) return null;

            return GitService.platformRunsHooks.test(repository()) ? null : installed;
        } catch (Exception e) {
            // An engine that cannot answer should not block committing; the hook may well
            // run. Logged because it means the check above is not protecting anything.
            LOGGER.log(Level.WARNING, "Could not determine whether hooks can run here", e);
            return null;
        }
    }

    /**
     * Writes resolved content back to the working tree and stages it.
     *
     * <p>Encoded the way the conflicted file on disk was read, not as UTF-8: the file
     * being resolved is rewritten whole, so a non-UTF-8 one would come back as
     * replacement characters everywhere the user did not touch.
     */
    public GitResult resolveConflict(String path, String content) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            File target = new File(repository().getWorkTree(), path);
            byte[] onDisk = target.isFile()
                    ? java.nio.file.Files.readAllBytes(target.toPath()) : new byte[0];
            java.nio.file.Files.write(target.toPath(), TextEncoding.encodeForEdit(content, onDisk));
            git().add().addFilepattern(path).call();
            return GitResult.ok("Resolved " + path);
        } catch (Exception e) {
            return GitResult.failed("Could not resolve " + path + ": " + describe(e));
        }
    }

    /**
     * Undoes a single hunk in the working-tree copy of {@code path}, the equivalent of
     * {@code git apply --reverse} for one hunk.
     *
     * <p>Locates the hunk's "after" content in the current file and swaps it back to the
     * "before" content. The search starts at the hunk's recorded position and widens
     * outward, so this works for a historical commit whose line numbers no longer line
     * up with the working tree - not just for a freshly generated working-tree diff.
     *
     * @param edit the hunk's "after" content to find and "before" content to restore,
     *             with the encoding to read the file as and what its last byte should be
     */
    public GitResult revertHunk(String path, HunkEdit edit) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            File target = new File(repository().getWorkTree(), path);
            if (!target.isFile()) {
                return GitResult.failed(path + " is not in the working tree");
            }
            if (isUnmerged(repository().readDirCache().getEntry(path))) {
                return GitResult.failed(unmergedRefusal(path));
            }

            byte[] originalBytes = java.nio.file.Files.readAllBytes(target.toPath());
            HunkPatcher.BytesResult patched = HunkPatcher.apply(originalBytes, edit);
            if (!patched.applied()) {
                return GitResult.failed(HunkPatcher.explain(patched.refusal(), path, false));
            }

            java.nio.file.Files.write(target.toPath(), patched.content());
            return GitResult.ok("Reverted hunk in " + path + patched.describeOffset());
        } catch (Exception e) {
            return GitResult.failed("Could not revert hunk: " + describe(e));
        }
    }

    /**
     * Rewrites part of the <em>index</em> copy of {@code path}, leaving the working tree
     * untouched. This is what stages or unstages a single hunk.
     *
     * <p>Whole-file staging copies the working tree over the index. Partial staging
     * cannot: the index has to end up holding something that exists in neither HEAD nor
     * the working tree - the file with only the chosen changes applied. So the index
     * blob is read, patched in memory, written back as a new blob, and the index entry
     * repointed at it. Git itself does exactly this for {@code git add -p}.
     *
     * <p>A file can legitimately appear in both the staged and unstaged lists afterwards.
     * That is not a bug; it is what partial staging means.
     *
     * @param edit   the block to find in the index copy and what to put in its place,
     *               with the encoding to read the blob as and what its last byte should be
     * @param action verb for the status message, e.g. "Staged hunk"
     */
    public GitResult applyToIndex(String path, HunkEdit edit, String action) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            if (isUnmerged(repository().readDirCache().getEntry(path))) {
                return GitResult.failed(unmergedRefusal(path));
            }
            byte[] originalBytes = getIndexBytes(path);
            HunkPatcher.BytesResult patched = HunkPatcher.apply(originalBytes, edit);
            if (!patched.applied()) {
                return GitResult.failed(HunkPatcher.explain(patched.refusal(), path, true));
            }

            if (patched.content().length == 0 && edit.removesPath()) {
                // Every line of a deletion staged, or of an addition unstaged: the path
                // leaves the index, as `git rm --cached` would, rather than staying as
                // an empty file nobody asked for.
                removeIndexEntry(path);
                return GitResult.ok(action + " in " + path + " - removed from the index");
            }
            writeIndexBlob(path, patched.content(), edit.createModeOrRegular());
            return GitResult.ok(action + " in " + path + patched.describeOffset());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not patch the index copy of " + path, e);
            return GitResult.failed("Could not update the index: " + describe(e));
        }
    }

    public byte[] getStagedBytes(String path) {
        if (repository() == null) return new byte[0];
        try {
            return getIndexBytes(path);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read the staged copy of " + path, e);
            return new byte[0];
        }
    }

    /** The staged copy of {@code path}; empty when the file is not in the index yet. */
    byte[] getIndexBytes(String path) throws Exception {
        DirCacheEntry entry = repository().readDirCache().getEntry(path);
        // An intent-to-add entry names the empty blob without git ever having written it,
        // so there may be no object to open.
        if (entry == null || entry.isIntentToAdd()
                || Constants.EMPTY_BLOB_ID.equals(entry.getObjectId())) {
            return new byte[0];
        }
        return ctx.readBlob(entry.getObjectId());
    }

    /** Whether {@code entry} is one stage of a conflict rather than a resolved path. */
    private static boolean isUnmerged(DirCacheEntry entry) {
        return entry != null && entry.getStage() != DirCacheEntry.STAGE_0;
    }

    /**
     * Why a conflicted path refuses hunk operations. Its diff is several sections - or a
     * combined diff - none of which is the file against the index, so no block taken from
     * it describes the content being patched; and writing a stage-0 entry would silently
     * mark the conflict resolved.
     */
    static String unmergedRefusal(String path) {
        return path + " has unresolved conflicts - resolve them before staging or reverting part of it";
    }

    /** Takes {@code path} out of the index altogether, leaving the working tree alone. */
    private void removeIndexEntry(String path) throws Exception {
        DirCache cache = repository().lockDirCache();
        try {
            DirCacheEditor editor = cache.editor();
            editor.add(new DirCacheEditor.DeletePath(path));
            editor.commit();
        } catch (Exception e) {
            cache.unlock();
            throw e;
        }
    }

    /**
     * Points the index entry for {@code path} at a new blob holding {@code content}.
     *
     * @param createMode the mode for a path with no entry yet, e.g. {@code "100755"} for a
     *                   new executable; an existing entry keeps its own
     */
    private void writeIndexBlob(String path, byte[] content, String createMode) throws Exception {
        ObjectId blobId;
        try (ObjectInserter inserter = repository().newObjectInserter()) {
            blobId = inserter.insert(Constants.OBJ_BLOB, content);
            inserter.flush();
        }

        DirCacheEntry existing = repository().readDirCache().getEntry(path);
        FileMode mode = existing != null
                ? existing.getFileMode()
                : FileMode.fromBits(Integer.parseInt(createMode, 8));

        DirCache cache = repository().lockDirCache();
        try {
            DirCacheEditor editor = cache.editor();
            editor.add(new DirCacheEditor.PathEdit(path) {
                public void apply(DirCacheEntry entry) {
                    if (entry.isIntentToAdd()) {
                        // `git add -N` marks the entry intent-to-add, and an entry with
                        // that flag is "not staged" whatever blob it names: the commit
                        // skips it. Writing real content is exactly the moment the intent
                        // becomes a fact, which is what git's own add does. JGit has no
                        // setter for the flag, so the entry takes a fresh entry's
                        // metadata - no extended flags at all - before the fields below
                        // are filled in again. (An intent-to-add path is never also
                        // skip-worktree, so nothing else is lost.)
                        entry.copyMetaData(new DirCacheEntry(path));
                    }
                    entry.setFileMode(mode);
                    entry.setObjectId(blobId);
                    // Zero the cached stat so Git re-reads the file instead of trusting
                    // it. The index no longer matches the working tree - that is the
                    // point - and stale size and mtime here would hide the remainder.
                    entry.setLength(0);
                    entry.setLastModified(java.time.Instant.EPOCH);
                }
            });
            editor.commit(); // writes the index and releases the lock
        } catch (Exception e) {
            cache.unlock();
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Commit template
    // ------------------------------------------------------------------

    /**
     * The contents of {@code commit.template}, read the way git reads it - so a
     * template set in a conditionally included file is found when git is installed.
     */
    public String getCommitTemplate() {
        if (repository() == null) return "";
        EffectiveConfig effective = EffectiveConfig.fromGit(repository());
        String configured = effective != null ? effective.get("commit.template")
                : repository().getConfig().getString("commit", null, "template");
        if (configured == null || configured.isBlank()) return "";
        File file = ctx.resolveConfiguredPath(configured.trim());
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not read commit.template " + file, e);
            return "";
        }
    }

    /**
     * The committer identity, resolved as {@link CommitPreflight} resolves it for a
     * commit - so a work email set through {@code includeIf} is the one signed off with,
     * not the personal one JGit's own config reading would find.
     */
    public String getCommitIdentity() {
        if (repository() == null) return null;
        PersonIdent committer = CommitPreflight.committer(repository());
        return committer == null ? null
                : committer.getName() + " <" + committer.getEmailAddress() + ">";
    }
}
