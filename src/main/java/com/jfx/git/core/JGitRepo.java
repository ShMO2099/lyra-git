package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * The JGit engine's shared context: the open repository's handles, opening and closing,
 * creating and cloning repositories, and the helpers every capability leans on - network
 * setup and credential bookkeeping, running a command as git's identity, reading commits
 * and blobs safely, and where git looks for hooks.
 *
 * <p>It also holds one instance of each capability class, which reach each other through
 * {@link JGitPart}. {@link GitService} is the public face; this is package-private.
 */
final class JGitRepo extends JGitPart {

    /** One of each capability, all sharing this context. */
    final JGitBranches branches = new JGitBranches(this);
    final JGitStaging staging = new JGitStaging(this);
    final JGitRemotes remotes = new JGitRemotes(this);
    final JGitIntegration integration = new JGitIntegration(this);
    final JGitRebase rebases = new JGitRebase(this);
    final JGitStash stashes = new JGitStash(this);
    final JGitHistory history = new JGitHistory(this);
    final JGitDiffs diffs = new JGitDiffs(this);
    final JGitBisect bisection = new JGitBisect(this);
    final JGitStructure structure = new JGitStructure(this);
    final JGitSnapshots snapshots = new JGitSnapshots(this);

    JGitRepo() {
        super(null);
    }

    // Volatile because lifecycle (open/close/engine switch) can run on a different
    // thread from the queries reading them; all git work itself is serialised through
    // the repository's GitTaskRunner lane.
    volatile Git git;
    volatile Repository repository;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Opens the repository containing {@code dir}.
     *
     * <p>Walks up to find the {@code .git} directory, so opening a subdirectory of a
     * working tree works the same way it does in commercial clients.
     *
     * <p>Walking up is also what makes git's {@code safe.directory} rule necessary, and
     * JGit has no such rule: a {@code C:\.git} planted by another user would be found
     * from any folder beneath it. So the ownership check git would make is made here -
     * see {@link SafeDirectory#ownershipProblem} - and a refusal is final.
     *
     * @throws DubiousOwnershipException when git would refuse this repository
     */
    public boolean openRepository(File dir) {
        return open(dir, true);
    }

    /**
     * Opens a repository another engine in the same composite has already opened -
     * and therefore already ownership-checked - at {@code workTree}.
     *
     * <p>Only for {@link HybridGitBackend} and the libgit2 engine's JGit delegate: the
     * check costs a {@code stat} per directory, and for a foreign-owned repository that
     * the user has trusted, a git subprocess - both already paid by the native half.
     */
    public boolean openAlreadyVerifiedRepository(File workTree) {
        return open(workTree, false);
    }

    private boolean open(File dir, boolean checkOwnership) {
        try {
            synchronized (this) { close(); }
            FileRepositoryBuilder builder = new FileRepositoryBuilder()
                    .findGitDir(dir)
                    .readEnvironment();
            if (builder.getGitDir() == null) {
                throw new GitOperationException("No git repository found at or above: " + dir.getAbsolutePath());
            }
            Repository opened = builder.build();
            if (checkOwnership) {
                String refusal = SafeDirectory.ownershipProblem(
                        opened.isBare() ? null : opened.getWorkTree(), opened.getDirectory());
                if (refusal != null) {
                    opened.close();
                    throw new DubiousOwnershipException(refusal);
                }
            }
            repository = opened;
            // Non-owning: we built the Repository, so we close it ourselves in close().
            git = new Git(repository);
            LOGGER.log(Level.INFO, "Opened repository at {0}", repository.getDirectory().getAbsolutePath());
            return true;
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Failed to open repository: " + describe(e), e);
        }
    }

    /** Creates a new repository, on the branch name Preferences names, with no starter files. */
    public static GitResult initRepository(File dir) {
        return initRepository(dir, RepoInitTemplates.NOTHING);
    }

    /**
     * Creates a new repository, on the branch name Preferences names, then writes
     * whichever starter files {@code templates} asks for (a {@code .gitignore},
     * a LICENSE, a README) into the working tree. Left unstaged, the same way
     * {@code restoreFileFromCommit} leaves its own result unstaged - the change stays
     * visible for the user to review and commit themselves rather than an
     * unreviewed "Initial commit" appearing on their behalf.
     */
    public static GitResult initRepository(File dir, RepoInitTemplates templates) {
        try (Git _ = Git.init().setDirectory(dir)
                .setInitialBranch(AppPreferences.defaultBranchName())
                .call()) {
            // The Git handle is only needed to confirm init succeeded; the template
            // files below are plain writes, independent of git.
        } catch (Exception e) {
            return GitResult.failed("Init failed: " + describe(e));
        }
        try {
            templates.writeInto(dir, dir.getName());
        } catch (IOException e) {
            return GitResult.failed("Repository created, but writing template files failed: " + describe(e));
        }
        return GitResult.ok("Initialized empty repository at " + dir.getAbsolutePath());
    }

    /**
     * A progress monitor whose only job is to answer "should I stop?".
     *
     * <p>JGit asks its monitor between objects, which is the one place a transfer can be
     * stopped without leaving the object database half-written - so this is what makes
     * clone, fetch, pull and push cancellable at all. It also passes JGit's stages and
     * counts on to whoever is watching the task ({@link GitTaskRunner#reportProgress}):
     * "Receiving objects", 45% - a no-op when nobody is.
     */
    private static org.eclipse.jgit.lib.ProgressMonitor cancelWhen(
            java.util.function.BooleanSupplier cancelled) {
        return new org.eclipse.jgit.lib.EmptyProgressMonitor() {
            private String stage = "";
            private int total;
            private int done;

            public void beginTask(String title, int totalWork) {
                stage = title == null ? "" : title;
                total = totalWork;
                done = 0;
                GitTaskRunner.reportProgress(stage, totalWork > 0 ? 0 : -1);
            }

            @Override
            public void update(int completed) {
                done += completed;
                if (total > 0) {
                    GitTaskRunner.reportProgress(stage, Math.min(1.0, done / (double) total));
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.getAsBoolean();
            }
        };
    }

    /**
     * The monitor for work submitted through {@link GitTaskRunner}, which raises a flag
     * on the task rather than passing one down through every signature.
     */
    private static org.eclipse.jgit.lib.ProgressMonitor taskCancellation() {
        return cancelWhen(GitTaskRunner::isCancelled);
    }

    /** Clones {@code uri} into {@code dir}, in full. Blocking - call from a background thread. */
    public static GitResult cloneRepository(String uri, File dir) {
        return cloneRepository(uri, dir, null, 0);
    }

    /**
     * Clones {@code uri} into {@code dir}. Blocking - call from a background thread.
     *
     * @param branch a specific branch to clone, or {@code null}/blank for the remote's
     *               default - {@link org.eclipse.jgit.api.CloneCommand#setBranch}
     *               accepts a short name directly
     * @param depth  a shallow clone's history depth ({@code git clone --depth}), or
     *               {@code 0} for the complete history
     */
    public static GitResult cloneRepository(String uri, File dir, String branch, int depth) {
        return cloneRepository(uri, dir, branch, depth, () -> false);
    }

    /**
     * Clones {@code uri} into {@code dir}, stopping when {@code cancelled} turns true.
     *
     * <p>The clone runs on the single git worker, so a large repository holds every other
     * repository's work behind it; with no way to stop one, a progress dialog's Cancel
     * button can only hide itself. JGit polls its progress monitor as it receives
     * objects, which is the cancellation point a clone has - prompt on a long transfer,
     * unnoticed on a short one.
     *
     * <p>What a cancelled or failed clone leaves is JGit's decision, not this method's:
     * {@code CloneCommand} cleans up after itself, removing a directory it created or
     * emptying one that already existed. The message used to promise the partial data
     * was kept, which sent users looking for a folder that was gone; it now reports
     * what is actually on disk.
     *
     * <p>Submodules are cloned too, as {@code git clone --recurse-submodules} would: a
     * clone whose submodule directories are empty does not build, and nothing on screen
     * said why.
     *
     * @param cancelled polled during the transfer; {@code true} stops the clone
     */
    public static GitResult cloneRepository(String uri, File dir, String branch, int depth,
                                            java.util.function.BooleanSupplier cancelled) {
        try {
            var command = Git.cloneRepository()
                    .setURI(uri)
                    .setDirectory(dir)
                    .setCloneSubmodules(true);
            if (branch != null && !branch.isBlank()) {
                command.setBranch(branch.trim());
            }
            if (depth > 0) {
                command.setDepth(depth);
            }
            // No profile SSH key: there is no repository yet to say which profile applies.
            try (Git _ = callRemote(command, null, cancelWhen(cancelled))) {
                return GitResult.ok("Cloned into " + dir.getAbsolutePath());
            }
        } catch (Exception e) {
            if (cancelled.getAsBoolean()) {
                return GitResult.failed(cancelledCloneMessage(dir));
            }
            String said = describe(e);
            return RefusalWords.inputFailure("Clone failed: " + said, RefusalWords.cloneField(said));
        }
    }

    /** What a cancelled clone left behind, described from the disk rather than assumed. */
    static String cancelledCloneMessage(File dir) {
        String[] left = dir.isDirectory() ? dir.list() : null;
        if (left != null && left.length > 0) {
            return "Clone cancelled. What had already been fetched is in "
                    + dir.getAbsolutePath() + ".";
        }
        return "Clone cancelled. Nothing was kept: the partial clone was removed.";
    }

    public boolean isOpen() {
        return git != null && repository != null;
    }

    public File getDirectory() {
        return repository == null ? null : repository.getDirectory();
    }

    /** Working tree root, used to resolve paths for discard/delete. */
    public File getWorkTree() {
        return repository == null ? null : repository.getWorkTree();
    }

    public synchronized void close() {
        if (git != null) {
            // Git.close() would also close the Repository we handed it; we built that
            // Repository ourselves, so close each exactly once and drop the references.
            git.close();
            git = null;
        }
        if (repository != null) {
            repository.close();
            repository = null;
        }
    }

    /**
     * How long a JGit transport may sit without receiving anything before it gives up.
     *
     * <p>An idle timeout, not a limit on the whole transfer: a large fetch that keeps
     * receiving is never cut off. Without one, a server that accepts the connection and
     * then says nothing held the single git worker indefinitely, and every other action
     * in every tab queued behind it with no way out but restarting.
     */
    static final int NETWORK_IDLE_TIMEOUT_SECONDS = 120;

    /**
     * Makes an SSH connection offer the key of the profile active on this repository, or
     * {@code null} - no callback - when the profile names none. See {@link SshKeys}.
     *
     * <p>Set on every command that talks to a remote, beside its credentials provider.
     * JGit's SSH transport is otherwise one process-wide factory with one set of default
     * keys, which cannot tell a personal repository from a work one.
     */
    private org.eclipse.jgit.api.TransportConfigCallback profileSshKey() {
        if (repository == null || repository.isBare()) {
            return null;
        }
        org.eclipse.jgit.transport.SshSessionFactory factory =
                SshSessionManager.factoryForKey(SshKeys.keyFor(repository.getWorkTree()));
        if (factory == null) {
            return null;
        }
        return transport -> {
            if (transport instanceof org.eclipse.jgit.transport.SshTransport ssh) {
                ssh.setSshSessionFactory(factory);
            }
        };
    }

    private static void credentialsWorked(boolean worked) {
        GitService.networkOutcome.accept(worked);
    }

    /**
     * Runs a command that talks to a remote, set up as every one here has to be: the idle
     * timeout, the credentials provider, the profile's SSH key, the task's cancellation
     * and progress - and with its outcome always reported to the credentials manager.
     *
     * <h2>Why one place</h2>
     * The setup used to be repeated at each call site, and five of them - pushing and
     * deleting a tag, adding and updating submodules, the ls-remote behind a push lease -
     * had drifted: no timeout on some, no SSH key on others, and none reported how the
     * call went. That last one is not bookkeeping. {@link
     * CredentialsManager#networkOperationFinished} approves every login still waiting on
     * the next success, so a token typed for one host and rejected by a tag push stayed
     * pending, and the next unrelated fetch that worked approved it into the user's
     * credential helper.
     */
    <T> T overNetwork(org.eclipse.jgit.api.TransportCommand<?, T> command)
            throws org.eclipse.jgit.api.errors.GitAPIException {
        return callRemote(command, profileSshKey(), taskCancellation());
    }

    /** {@link #overNetwork} for a caller with no repository open yet: a clone. */
    private static <T> T callRemote(org.eclipse.jgit.api.TransportCommand<?, T> command,
                                    org.eclipse.jgit.api.TransportConfigCallback sshKey,
                                    org.eclipse.jgit.lib.ProgressMonitor monitor)
            throws org.eclipse.jgit.api.errors.GitAPIException {
        command.setTimeout(NETWORK_IDLE_TIMEOUT_SECONDS);
        command.setCredentialsProvider(CredentialsManager.getInstance().jgit());
        command.setTransportConfigCallback(sshKey);
        // Each command declares its own setProgressMonitor; there is no common one.
        switch ((Object) command) {
            case org.eclipse.jgit.api.FetchCommand c -> c.setProgressMonitor(monitor);
            case org.eclipse.jgit.api.PushCommand c -> c.setProgressMonitor(monitor);
            case org.eclipse.jgit.api.PullCommand c -> c.setProgressMonitor(monitor);
            case org.eclipse.jgit.api.CloneCommand c -> c.setProgressMonitor(monitor);
            case org.eclipse.jgit.api.SubmoduleAddCommand c -> c.setProgressMonitor(monitor);
            case org.eclipse.jgit.api.SubmoduleUpdateCommand c -> c.setProgressMonitor(monitor);
            default -> { }   // ls-remote has no progress to report
        }
        boolean worked = false;
        try {
            T result = command.call();
            worked = true;
            return result;
        } finally {
            credentialsWorked(worked);
        }
    }

    /**
     * A blob's whole content, however large.
     *
     * <p>Not {@code ObjectLoader.getBytes()}: that throws {@code LargeObjectException} for
     * anything above {@code core.streamFileThreshold} (50 MiB by default), and every
     * caller here answered an exception with an empty array - so a large file read as
     * empty, and its diff as the file having been emptied or deleted. A large object is
     * streamed into its own buffer instead. Past what one array can hold it still
     * throws, with JGit's message naming the size.
     */
    byte[] readBlob(ObjectId id) throws IOException {
        ObjectLoader loader = repository.open(id, Constants.OBJ_BLOB);
        // getCachedBytes(limit) streams a large object into a fresh array; a small one's
        // cached array may be shared with JGit's delta cache, so it is copied as before.
        return loader.isLarge() ? loader.getCachedBytes(Integer.MAX_VALUE) : loader.getBytes();
    }

    /**
     * {@code rev} parsed as a commit, or {@code null} when it names nothing.
     *
     * <p>{@code walk.parseCommit(repository.resolve(rev))} threw a NullPointerException
     * from inside JGit for a name that resolves to nothing - a branch deleted elsewhere -
     * which was then logged at WARNING with a stack trace, as though the repository were
     * broken. A full hash resolves whether or not the object exists, so a commit gone
     * since its graph row was drawn is caught here as well.
     */
    RevCommit commitOrNull(RevWalk walk, String rev) throws IOException {
        ObjectId id = rev == null ? null : repository.resolve(rev);
        if (id == null) return null;
        try {
            return walk.parseCommit(id);
        } catch (org.eclipse.jgit.errors.MissingObjectException gone) {
            return null;
        }
    }

    /**
     * Runs a JGit command that writes commits of its own, as the identity git would use.
     *
     * <h2>Why</h2>
     * {@link JGitStaging#commit} goes through {@link CommitPreflight}, which reads identity and
     * signing the way git does. Merge, pull, rebase, cherry-pick, revert, stash and tag
     * did not: JGit's own commands take the committer from JGit's reading of the config,
     * which drops {@code includeIf} - so in a work repository whose email comes from an
     * included file, every merge, rebase and cherry-pick was recorded under the personal
     * one, and pushed that way for good. With no identity at all JGit invented
     * {@code osuser@host}, where the commit box refused.
     *
     * <p>None of those commands takes a committer, so the effective identity is set on
     * the repository's in-memory config for the duration of the call - unsaved, and read
     * back from disk afterwards. Signing is turned off for the same span, as
     * {@code commit} does: the preflight has already refused where signing was required.
     * Nothing inside may save the repository's config while this stands; see
     * {@link JGitRemotes#pull} for the one caller that had to move work out of the way.
     *
     * @param whenRefused what to do when the preflight refuses - an operation that may
     *                    not need a commit at all can still go ahead without one; {@code
     *                    null} refuses
     */
    GitResult asGit(java.util.function.Supplier<GitResult> op,
                            java.util.function.Function<String, GitResult> whenRefused) {
        CommitPreflight.Outcome preflight = CommitPreflight.evaluate(repository);
        if (preflight.refusal() != null) {
            return whenRefused == null
                    ? GitResult.failed(preflight.refusal())
                    : whenRefused.apply(preflight.refusal());
        }
        PersonIdent who = preflight.plan().committer();
        org.eclipse.jgit.lib.StoredConfig config = repository.getConfig();
        config.setString("user", null, "name", who.getName());
        config.setString("user", null, "email", who.getEmailAddress());
        config.setBoolean("commit", null, "gpgsign", false);
        try {
            return op.get();
        } finally {
            try {
                config.load();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not reload the repository config", e);
            }
        }
    }

    /**
     * The directory git would run hooks from - {@code git rev-parse --git-path hooks}.
     *
     * <p>Two details git gets right and a plain {@code .git/hooks} does not. A linked
     * worktree's git directory is {@code .git/worktrees/<name>}, which has no hooks of its
     * own: they live in the common directory every worktree shares. And
     * {@code core.hooksPath = ~/hooks} names the user's home, which git expands like
     * any other path-valued setting.
     */
    File hooksDirectory() {
        String configured = repository.getConfig().getString("core", null, "hooksPath");
        if (configured != null && !configured.isBlank()) {
            return resolveConfiguredPath(configured.trim());
        }
        File common = repository.getCommonDirectory();
        return new File(common != null ? common : repository.getDirectory(), "hooks");
    }

    /**
     * A path-valued setting as git reads it: {@code ~/} is the home directory, and a
     * relative path is relative to the work tree (the git directory when bare).
     */
    File resolveConfiguredPath(String path) {
        if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
            String rest = path.length() > 2 ? path.substring(2) : "";
            return new File(System.getProperty("user.home"), rest);
        }
        File asGiven = new File(path);
        if (asGiven.isAbsolute()) return asGiven;
        File base = repository.isBare() ? repository.getDirectory() : repository.getWorkTree();
        return base == null ? asGiven : new File(base, path);
    }
}
