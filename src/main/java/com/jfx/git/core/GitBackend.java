package com.jfx.git.core;

import java.io.File;

/**
 * The contract a Git backend must satisfy, composed from one interface per capability.
 *
 * <p>All types that cross this boundary are plain Java — no JGit, libgit2 or subprocess
 * token leaks through. The goal is to let the UI layer stay identical regardless of
 * whether work is done by JGit, a native {@code git} process, or a libgit2 binding.
 *
 * <h2>Why it is split</h2>
 * This was one interface of about ninety methods, and every feature added more. The
 * methods were already grouped by banner comments; the groups are now real types, so a
 * reader can open the twenty methods that concern staging without scrolling past rebase
 * and blame, and a future engine can be read against one capability at a time.
 *
 * <p>The split is deliberately <em>not</em> a claim that an engine may implement only
 * some of them. {@code GitBackend} is what everything is written against, and every
 * implementation still provides the whole surface; several methods carry defaults that
 * refuse by name where an engine genuinely cannot do the work (sparse checkout under
 * JGit, for instance). Splitting the declaration is about reading and reviewing cost,
 * not about optional conformance.
 *
 * <dl>
 *   <dt>{@link RepositoryOps}</dt><dd>Opening a repository and where it lives.</dd>
 *   <dt>{@link BranchOps}</dt><dd>Branches, remote-tracking branches, tags.</dd>
 *   <dt>{@link StagingOps}</dt><dd>The index, partial staging, committing.</dd>
 *   <dt>{@link RemoteOps}</dt><dd>Fetch, pull, push, and the remote list.</dd>
 *   <dt>{@link IntegrationOps}</dt><dd>Merge, rebase, cherry-pick, revert, reset, stash.</dd>
 *   <dt>{@link HistoryOps}</dt><dd>Graph, diffs, blame, reflog, bisect.</dd>
 *   <dt>{@link RepoStructureOps}</dt><dd>Submodules, worktrees, sparse checkout.</dd>
 *   <dt>{@link LfsOps}</dt><dd>Git LFS: tracked patterns, fetch, prune, locks.</dd>
 * </dl>
 *
 * <h2>Design intent</h2>
 * <ul>
 *   <li>{@link GitService} is the reference implementation (JGit).</li>
 *   <li>{@link NativeGitBackend} shells out to {@code git} and parses its output;
 *       {@link com.jfx.git.core.libgit2.Libgit2Backend} calls libgit2 through the
 *       Foreign Function &amp; Memory API and delegates the rest.</li>
 *   <li>Methods that are purely one engine's internal helpers are deliberately absent —
 *       they are implementation details, not API.</li>
 *   <li>So are the conveniences and policies callers build from these methods - a merge
 *       by flag, a stash addressed by its commit, the restore that refuses a dirty tree:
 *       {@link RepositoryCommands} has them, where no engine can override them.</li>
 *   <li>A failure an engine can say more about carries a {@link Refusal}
 *       ({@link GitResult#getRefusal()}), set where the engine read git's words or caught
 *       JGit's exception; callers ask it rather than read the message.</li>
 *   <li>The engine-neutral types these methods exchange live in
 *       {@link com.jfx.git.model} (and {@link com.jfx.git.graph.GraphPage} beside the
 *       rows it carries), not as nested types on any one engine.</li>
 * </ul>
 *
 * <h2>Threading: lane-confined</h2>
 * An engine is called from its repository's {@link GitTaskRunner} lane and nowhere else,
 * one call at a time - each open repository has a lane of its own, so two tabs' work runs
 * side by side while one repository's never does. Implementations are not built for
 * concurrent callers and do not try to
 * be: they hold an open repository, a cached index, a subprocess runner, and a call made
 * alongside another - or alongside {@link #close()} - can find any of those replaced or
 * gone. Serialising on the lane is what makes that safe; closing and switching engines run
 * there too, so neither lands under a call in progress.
 *
 * <p>This used to say implementations "must be safe to call from any thread", which no
 * engine was and the task runner never relied on. Calls from the FX thread, and from
 * another repository's lane, are reported by {@link ThreadGuard} - for every engine, at
 * {@link SwitchableGitBackend}, the indirection every UI call passes through.
 */
public interface GitBackend extends
        RepositoryOps, BranchOps, StagingOps, RemoteOps,
        IntegrationOps, HistoryOps, RepoStructureOps, LfsOps {

    // ------------------------------------------------------------------
    // Shared vocabulary
    // ------------------------------------------------------------------
    //
    // Static helpers stay on the composing root rather than on one capability
    // interface: they are facts about git that several capabilities consult, and a
    // static method on an interface is not inherited by its sub-interfaces, so moving
    // one would only make its callers name a less obvious type.

    /**
     * Namespace for refs the app writes for its own use.
     *
     * <p>Under {@code refs/remotes/} such a ref would show up in the sidebar's
     * remote-branch tree and decorate rows in the graph, and no fetch refspec covers it,
     * so {@code --prune} never removes it either. Anything the app needs a ref for but
     * the user did not ask for goes here instead.
     */
    String INTERNAL_REF_PREFIX = "refs/lyra/";

    /**
     * {@link #INTERNAL_REF_PREFIX} from before the application was renamed. Snapshots
     * under it are moved across when a repository's snapshots are first touched; the
     * rest - pull-request and review checkouts - stay hidden wherever they are.
     */
    String LEGACY_INTERNAL_REF_PREFIX = "refs/jfxgit/";

    /**
     * Whether a full ref name is one the app fabricated rather than one the user's
     * remote actually has.
     *
     * <p>Earlier versions parked pull-request checkouts at
     * {@code refs/remotes/<remote>/pr/<n>} and review checkouts at
     * {@code refs/remotes/_pr-checkout/<branch>}. Both are written under
     * {@link #INTERNAL_REF_PREFIX} now, but repositories carry the old ones forever -
     * nothing prunes a ref no refspec covers - so listings still filter them out.
     *
     * <p>The {@code pr/} test insists on an all-digits final segment, because that is the
     * only shape this app ever wrote; a real remote branch called {@code pr/login-fix}
     * stays visible.
     */
    static boolean isSyntheticRemoteRef(String fullRefName) {
        if (fullRefName == null) return false;
        if (fullRefName.startsWith(INTERNAL_REF_PREFIX)) return true;
        if (fullRefName.startsWith(LEGACY_INTERNAL_REF_PREFIX)) return true;
        if (fullRefName.startsWith("refs/remotes/_pr-checkout/")) return true;
        int pr = fullRefName.indexOf("/pr/");
        if (fullRefName.startsWith("refs/remotes/") && pr > 0) {
            String tail = fullRefName.substring(pr + 4);
            return !tail.isEmpty() && tail.chars().allMatch(Character::isDigit);
        }
        return false;
    }

    /**
     * True while a merge, cherry-pick, revert or rebase is still waiting for the commit
     * that finishes it - JGit spells this {@code RepositoryState != SAFE}.
     *
     * <p>A conflict resolved by taking one side verbatim leaves the working tree, the
     * index and HEAD in perfect agreement, so a staging-state check alone says "clean"
     * while the operation is still open. The WIP row is the only place this application
     * offers to make the finishing commit, so an engine that misses this state takes away
     * the user's way of completing the merge.
     *
     * <p>Judged from the marker files git itself writes, so it is the same answer on any
     * engine with a {@code .git} directory to look at.
     */
    static boolean isOperationInProgress(File gitDir) {
        if (gitDir == null) return false;
        return new File(gitDir, "MERGE_HEAD").exists()
                || new File(gitDir, "CHERRY_PICK_HEAD").exists()
                || new File(gitDir, "REVERT_HEAD").exists()
                || new File(gitDir, "rebase-merge").isDirectory()
                || new File(gitDir, "rebase-apply").isDirectory();
    }

    /** Seven characters, the length every display path in this application uses. */
    static String shortenHash(String hash) {
        return hash != null && hash.length() > 7 ? hash.substring(0, 7) : String.valueOf(hash);
    }
}
