package com.jfx.git.core;

import com.jfx.git.model.Submodule;
import java.io.File;
import java.util.List;

/**
 * The shape of the checkout itself: submodules, linked worktrees and sparse checkout.
 *
 * <p>The changing operations that an engine can legitimately lack - setting or turning
 * off sparse checkout, maintenance - have defaults that refuse by name, which beats a
 * method an engine has to implement dishonestly. What only reads is abstract: an empty
 * default there answered "none" for an engine that had simply never been asked.
 *
 * <p>One of the capability interfaces {@link GitBackend} composes; see its javadoc for
 * why the contract is split up rather than kept as one list.
 */
public interface RepoStructureOps {

    // ------------------------------------------------------------------
    // Sparse Checkout
    // ------------------------------------------------------------------

    /**
     * Whether the working tree is currently restricted to a subset of the repository.
     *
     * <p>Every engine answers this, including JGit, which cannot <em>change</em> sparse
     * checkout but can still read {@code core.sparseCheckout}. A default of {@code false}
     * used to be the JGit engine's answer, and it was wrong: a repository made sparse by
     * the CLI read as a full checkout there. {@code BackendConformanceTest} checks every
     * engine agrees.
     */
    boolean isSparseCheckoutEnabled();

    /**
     * The cone-mode directory patterns currently in effect, empty when sparse checkout
     * is off or unsupported. Order matches {@code git sparse-checkout list}'s own.
     */
    List<String> getSparseCheckoutPaths();

    /**
     * Restricts the working tree to exactly these top-level paths, in cone mode -
     * {@code git sparse-checkout init --cone} followed by {@code git sparse-checkout
     * set <paths>}. Top-level files are always included by cone mode itself regardless
     * of what is asked for here; {@code paths} names directories to include beyond
     * those. An engine with no sparse-checkout support refuses outright rather than
     * silently keeping the full tree, the same "name the fix" refusal {@code
     * GitService.commit} already gives when GPG signing is on under JGit.
     */
    default GitResult setSparseCheckoutPaths(List<String> paths) {
        return GitResult.failed("Sparse checkout needs the Native Git engine.");
    }

    /**
     * Restores the full working tree - {@code git sparse-checkout disable}. Same
     * engine-limitation refusal as {@link #setSparseCheckoutPaths} for an engine that
     * was never sparse to begin with.
     */
    default GitResult disableSparseCheckout() {
        return GitResult.failed("Sparse checkout needs the Native Git engine.");
    }

    // ------------------------------------------------------------------
    // Submodules
    // ------------------------------------------------------------------

    List<Submodule> getSubmodules();
    GitResult updateSubmodules(List<String> paths);
    GitResult addSubmodule(String url, String path);
    GitResult syncSubmodules();
    GitResult deinitSubmodule(String path, boolean force);
    File      submoduleWorkTree(String path);

    // ------------------------------------------------------------------
    // Worktrees
    // ------------------------------------------------------------------

    List<Worktrees.Worktree> getWorktrees();
    GitResult addWorktree(File path, String branch, boolean createBranch);
    GitResult removeWorktree(File path, boolean force);
    GitResult pruneWorktrees();
    GitResult setWorktreeLocked(File path, boolean locked);

    // ------------------------------------------------------------------
    // Maintenance
    // ------------------------------------------------------------------

    /**
     * Runs git's own housekeeping - {@code git maintenance run --auto}, or {@code gc
     * --auto} where that is not available.
     *
     * <p>A repository that has never been packed is slow at everything this application
     * does: every graph page, every diff, every status walks loose objects. Git does this
     * on its own during some operations, but a client that mostly reads can go a long
     * time without triggering it, and the user has no reason to suspect that the fix for
     * "this repository feels slow" is a command they have never heard of.
     *
     * <p>{@code --auto} rather than a full repack: it does nothing unless git's own
     * thresholds say there is something worth doing, so this is safe to offer as a
     * button rather than as a warning about how long it will take.
     */
    default GitResult runMaintenance() {
        return GitResult.failed("Maintenance needs the Native Git engine.");
    }

    /**
     * Whether git thinks this repository would benefit from {@link #runMaintenance()}.
     *
     * <p>Used to decide whether to offer it at all, so a healthy repository never sees
     * the suggestion. Answering {@code false} when unsure is deliberate: an unnecessary
     * nudge about internals is worse than a missing one.
     */
    boolean needsMaintenance();

    // ------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------

    /**
     * The hooks this repository would actually run, newest-relevant first.
     *
     * <p>Hooks are the most surprising thing in a repository: they run other people's
     * code on your commit, they live in a directory nothing shows you, and when one of
     * them rejects a commit the message comes from the script rather than from git. This
     * application already offers to skip them, which is a switch for a thing the user
     * cannot see. Listing them is the other half of that.
     *
     * <p>Only executable, non-sample files count - git ships {@code .sample} scripts in
     * every new repository and never runs them, so reporting those would be noise
     * indistinguishable from the real thing.
     */
    List<Hook> getHooks();

    /**
     * One hook script found in the repository.
     *
     * @param name       the hook's git name, e.g. {@code pre-commit}
     * @param path       where the script lives, which is not always {@code .git/hooks} -
     *                   {@code core.hooksPath} moves it, and a shared team hooks
     *                   directory is exactly the case where a user does not know what
     *                   is running
     * @param executable whether git will run it; a non-executable hook is silently
     *                   ignored by git, which is a common and confusing mistake on Unix
     */
    record Hook(String name, String path, boolean executable) {
    }
}
