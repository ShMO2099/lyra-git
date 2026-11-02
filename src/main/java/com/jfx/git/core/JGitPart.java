package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * What every piece of the JGit engine shares: the context holding the open repository,
 * the way to each other capability, and the small helpers they all use.
 *
 * <p>{@link GitService} was one class of nearly seven thousand lines; it is now the
 * public facade over these package-private parts, one per capability.
 */
abstract class JGitPart {

    /** The context every part shares; a {@link JGitRepo} is its own. */
    final JGitRepo ctx;

    JGitPart(JGitRepo ctx) {
        this.ctx = ctx != null ? ctx : (JGitRepo) this;
    }

    /**
     * The open repository's porcelain and repository, or {@code null} when none is open.
     * Read from the volatile fields on every call, exactly as the single class read them
     * before it was split: lifecycle can run on another thread from the queries.
     */
    Git git() { return ctx.git; }

    Repository repository() { return ctx.repository; }

    JGitBranches branches() { return ctx.branches; }
    JGitStaging staging() { return ctx.staging; }
    JGitRemotes remotes() { return ctx.remotes; }
    JGitIntegration integration() { return ctx.integration; }
    JGitRebase rebases() { return ctx.rebases; }
    JGitStash stashes() { return ctx.stashes; }
    JGitHistory history() { return ctx.history; }
    JGitDiffs diffs() { return ctx.diffs; }
    JGitBisect bisection() { return ctx.bisection; }
    JGitStructure structure() { return ctx.structure; }

    static final Logger LOGGER = Logger.getLogger(GitService.class.getName());

    static String shorten(String hash) {
        return hash != null && hash.length() > 8 ? hash.substring(0, 8) : String.valueOf(hash);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static String shortId(String commitish) {
        return commitish != null && commitish.length() > 7 ? commitish.substring(0, 7) : String.valueOf(commitish);
    }

    /** Several JGit exceptions carry a null message; fall back to the type name. */
    static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return msg;
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    /**
     * {@code e} as a failed result, its message after {@code prefix}, carrying
     * {@link Refusal.LocalChanges} when it is JGit refusing to check files out over local
     * changes - either of its two {@code CheckoutConflictException}s, anywhere in the
     * cause chain. For the catch blocks of everything that checks files out: checkouts,
     * merges, pulls, picks, reverts, resets, rebases and stash applies.
     */
    GitResult failure(String prefix, Exception e) {
        List<String> inTheWay = checkoutConflictPaths(e);
        String message = prefix + describe(e);
        return inTheWay == null ? GitResult.failed(message) : GitResult.refused(message, localChangesIn(inTheWay));
    }

    /** The paths of a {@code CheckoutConflictException} in {@code e}'s chain, or {@code null} when there is none. */
    static List<String> checkoutConflictPaths(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.eclipse.jgit.api.errors.CheckoutConflictException conflict) {
                return conflict.getConflictingPaths() == null ? List.of() : conflict.getConflictingPaths();
            }
            if (t instanceof org.eclipse.jgit.errors.CheckoutConflictException conflict) {
                return conflict.getConflictingFiles() == null ? List.of() : List.of(conflict.getConflictingFiles());
            }
            if (t.getCause() == t) break;
        }
        return null;
    }

    /**
     * Local changes to {@code paths} in the way. JGit names the files without saying what
     * kind of change each is, where git says it in words; so they count as untracked when
     * any of them is a file the index does not have.
     */
    Refusal.LocalChanges localChangesIn(Collection<String> paths) {
        List<String> named = paths == null ? List.of() : paths.stream().sorted().toList();
        return new Refusal.LocalChanges(named, anyUntracked(named));
    }

    private boolean anyUntracked(List<String> paths) {
        Repository repository = repository();
        if (paths.isEmpty() || repository == null || repository.isBare()) return false;
        try {
            org.eclipse.jgit.dircache.DirCache index = repository.readDirCache();
            for (String path : paths) {
                if (index.findEntry(path) < 0 && new java.io.File(repository.getWorkTree(), path).exists()) {
                    return true;
                }
            }
        } catch (java.io.IOException e) {
            LOGGER.log(java.util.logging.Level.FINE, "Could not read the index", e);
        }
        return false;
    }

    /**
     * git's refusal when {@code what} would overwrite local changes to {@code paths} -
     * "Your local changes to the following files would be overwritten by merge: …" - with
     * the {@link Refusal.LocalChanges} beside it.
     */
    GitResult refusedOver(String what, Collection<String> paths) {
        return GitResult.refused(overwriteWords(what, paths), localChangesIn(paths));
    }

    /**
     * {@link #refusedOver(String, Collection)} from JGit's reasons per path: a path it
     * could not delete is named as that rather than as a local edit, and when that is all
     * there is, the failure is no refusal - a stash would not help.
     */
    GitResult refusedOver(String what,
                          Map<String, org.eclipse.jgit.merge.ResolveMerger.MergeFailureReason> failing) {
        List<String> dirty = new ArrayList<>();
        List<String> undeletable = new ArrayList<>();
        for (var entry : new java.util.TreeMap<>(failing).entrySet()) {
            (entry.getValue() == org.eclipse.jgit.merge.ResolveMerger.MergeFailureReason.COULD_NOT_DELETE
                    ? undeletable : dirty).add(entry.getKey());
        }
        String message = dirty.isEmpty() ? "" : overwriteWords(what, dirty);
        if (!undeletable.isEmpty()) {
            message += (message.isEmpty() ? "" : " ") + "Could not delete " + pathList(undeletable)
                    + " - is something holding it open?";
        }
        return GitResult.refused(message, dirty.isEmpty() ? null : localChangesIn(dirty));
    }

    /** git's "Your local changes to the following files would be overwritten by merge: …". */
    private static String overwriteWords(String what, Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return "Your local changes would be overwritten by " + what
                    + ". Commit your changes or stash them to proceed.";
        }
        return "Your local changes to the following files would be overwritten by " + what
                + ": " + pathList(paths) + ". Commit your changes or stash them to proceed.";
    }

    /** At most ten paths, then a count - an index-wide refusal can name thousands. */
    static String pathList(Collection<String> paths) {
        List<String> sorted = paths.stream().sorted().toList();
        if (sorted.size() <= 10) return String.join(", ", sorted);
        return String.join(", ", sorted.subList(0, 10)) + " and " + (sorted.size() - 10) + " more";
    }
}
