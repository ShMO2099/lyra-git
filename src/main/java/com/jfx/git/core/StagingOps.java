package com.jfx.git.core;

import com.jfx.git.model.StagingState;
import java.io.File;
import java.util.List;

/**
 * The index: what is staged, what is not, and the commit that turns one into the other.
 * Includes partial staging, which rewrites the index copy of a file directly.
 *
 * <p>One of the capability interfaces {@link GitBackend} composes; see its javadoc for
 * why the contract is split up rather than kept as one list.
 */
public interface StagingOps {

    // ------------------------------------------------------------------
    // Staging area
    // ------------------------------------------------------------------

    /**
     * {@code true} when there are uncommitted changes (staged or unstaged), or an
     * operation is still waiting for its finishing commit.
     *
     * @see GitBackend#isOperationInProgress(File)
     */
    boolean hasUncommittedChanges() throws GitOperationException;

    List<String> getStagedFiles();
    List<String> getConflictingFiles();

    /**
     * How many lines each changed file gains and loses, keyed by path.
     *
     * <p>The same number the commit panel already shows for a commit, for work that is
     * not committed yet. Without it a staging list says which files changed and gives no
     * sense of scale, so a one-character typo fix and a rewritten module look alike in
     * the one view where the difference decides what you review first.
     *
     * <h2>Absent, not zero</h2>
     * A path missing from the map means "not known" - a binary file, or an engine that
     * cannot answer - and the caller shows nothing rather than {@code +0} which would
     * read as a file that changed by nothing. The values are {@code {added, deleted}}.
     *
     * <h2>Why the caller supplies the paths</h2>
     * The unstaged side is index-versus-working-tree, and a plain walk of the working
     * tree visits everything on disk - including the ignored build output that is the
     * whole reason a repository has a {@code .gitignore}. Bounding the walk to the paths
     * already on screen keeps it proportional to what is being shown rather than to what
     * happens to be in the directory, and the caller has that list in hand because it
     * just drew it. An engine that asks git directly may ignore the hint; git excludes
     * ignored and untracked files from a diff by itself.
     *
     * @param staged true for what is in the index, false for what is only in the tree
     * @param paths  the paths the caller is displaying; empty asks for nothing
     */
    java.util.Map<String, int[]> getWorkingTreeLineCounts(boolean staged, List<String> paths);

    StagingState getStagingState();

    GitResult stageFile(String path);
    GitResult stageAll();
    GitResult unstageFile(String path);
    GitResult unstageAll();
    GitResult discardChanges(String path);

    /**
     * Throws away every working-tree change at once — the trash-icon contract:
     * modified and deleted tracked files are restored to HEAD's version, untracked
     * files are removed. Staged-but-not-yet-committed content survives in the index;
     * this restores the working tree, it does not reset the index.
     *
     * @return a result naming what happened; never throws for an ordinary git failure
     */
    GitResult discardAllChanges();

    // ------------------------------------------------------------------
    // Committing
    // ------------------------------------------------------------------

    GitResult commit(String message, boolean amend);

    /**
     * Commits, optionally bypassing the {@code pre-commit} and {@code commit-msg} hooks
     * - {@code git commit --no-verify}. Unlike GPG signing, this is not an engine
     * limitation: JGit's {@code CommitCommand} runs those two hooks for real when
     * scripts exist under {@code core.hooksPath} (verified against JGit 7.2's own
     * source - {@code Hooks.preCommit}/{@code commitMsg}, each a no-op only when no
     * script is present), and already exposes {@code setNoVerify(boolean)} to skip
     * them, the exact primitive this method needs. Both engines honour this correctly.
     */
    GitResult commit(String message, boolean amend, boolean skipHooks);

    /** Message of the most recent commit, for pre-filling an amend dialog. */
    String getHeadCommitMessage();

    /**
     * The contents of the file {@code commit.template} names, or {@code ""} when there is
     * none - what {@code git commit} would put in the editor before the user typed.
     *
     * <p>Comment lines are returned as they are; stripping them is the composer's job,
     * because the message it builds is cleaned with whitespace cleanup, which keeps them.
     */
    String getCommitTemplate();

    /**
     * Who the next commit would be committed by, as {@code Name <email>} - what
     * {@code git commit --signoff} writes after {@code Signed-off-by:}. Read the way git
     * reads it: environment, then {@code committer.*}, then {@code user.*}, with
     * conditional includes evaluated.
     *
     * @return {@code null} when no identity is configured - git's "Please tell me who
     *         you are"; never an invented {@code <osuser>@<hostname>}
     */
    String getCommitIdentity();

    /** Full SHA-1 HEAD points at, or {@code null} in an empty or unreadable repository. */
    String getHeadHash();

    /**
     * Writes {@code user.name}/{@code user.email}/{@code user.signingkey}/{@code
     * commit.gpgsign} into this repository's own {@code .git/config} - the local
     * override that wins over whatever the global identity says, without touching that
     * global identity at all. This is how applying a {@link com.jfx.git.model.Profile}
     * to one repository takes effect: both {@link GitService#commit} and {@link
     * NativeGitBackend#commit} already delegate author/committer resolution entirely to
     * their engine's own config chain (local overrides global overrides system), so
     * neither commit path needs to change - writing the local override here is the
     * whole mechanism.
     *
     * @param signingKey written to {@code user.signingkey} when non-blank; unset (not
     *                   written as empty) when blank, so a profile with no explicit key
     *                   falls through to gpg's own default rather than an empty one
     */
    GitResult setLocalIdentity(String name, String email, String signingKey, boolean gpgSign);

    /**
     * One value from the repository's own configuration - {@code .git/config}, not the
     * user's global file - or {@code null} when the key is not set there.
     *
     * <p>For settings that belong to the repository and that git itself does not
     * interpret, such as {@code gitflow.branch.develop}. What git would actually use for
     * a key it does interpret is a different question, with includes and global values
     * in it, and is not this method's to answer.
     *
     * @param key {@code section.name} or {@code section.subsection.name}
     */
    String getLocalConfig(String key);

    /**
     * Sets one value in the repository's own configuration. {@code null} removes the
     * key; an empty string is stored as an empty value, which is not the same thing.
     */
    GitResult setLocalConfig(String key, String value);

    // ------------------------------------------------------------------
    // Staged content helpers (used by in-app editor)
    // ------------------------------------------------------------------

    /**
     * The staged copy of {@code path} as raw bytes; empty when it is not in the index.
     * Decoded by the caller, with the user's own choice of encoding, as the diff viewer
     * does when it reveals the lines between the hunks of a staged diff.
     */
    byte[] getStagedBytes(String path);
}
