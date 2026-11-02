package com.jfx.git.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The native engine's shared context: the repository it is open on, the runner, the
 * open/closed state, the network environment and the configuration snapshot every
 * capability class reads through.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliRepo {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    final CliStaging staging = new CliStaging(this);
    final CliBranches branches = new CliBranches(this);
    final CliRemotes remotes = new CliRemotes(this);
    final CliIntegration integration = new CliIntegration(this);
    final CliStash stashes = new CliStash(this);
    final CliBisect bisection = new CliBisect(this);
    final CliHistory history = new CliHistory(this);
    final CliDiffs diffs = new CliDiffs(this);
    final CliStructure structure = new CliStructure(this);
    final CliLfs lfsPart = new CliLfs(this);
    final CliSnapshots snapshots = new CliSnapshots(this);

    /*
     * Volatile, and never nulled by close(): a tab closes on the UI thread while its last
     * refresh may still be running on a worker, and that refresh is past ensureOpen() by
     * then. With the fields nulled under it, its next `runner.run(...)` or
     * `new File(gitDir, ...)` was a NullPointerException in the middle of an operation.
     * close() swaps in a runner that refuses everything instead, so whatever was in flight
     * fails at its next git command with an ordinary result, and keeps valid paths for any
     * file it reads in between. `open` is what isOpen() and the getters answer from.
     */
    volatile File workTree;
    volatile File gitDir;
    volatile NativeGitRunner runner;
    volatile boolean open;

    public boolean openRepository(File dir) throws GitOperationException {
        if (dir == null || !dir.exists()) {
            throw new GitOperationException("Directory does not exist: " + dir);
        }

        NativeGitRunner testRunner = new NativeGitRunner(dir);
        var res = testRunner.run("rev-parse", "--show-toplevel", "--git-dir");
        if (!res.isOk() && SafeDirectory.isDubiousOwnership(res.stderr())) {
            // Git's own words, which name the folder and the command that trusts it. As
            // "Not a git repository" this read as a wrong folder, and a composite engine
            // could not tell it from a failure worth falling back over.
            throw new DubiousOwnershipException(res.stderr());
        }
        if (!res.isOk()) {
            throw new GitOperationException("Not a git repository: " + dir + " (" + res.stderr() + ")");
        }

        String[] lines = res.trimmedStdout().split("\\r?\\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new GitOperationException("Could not determine repository top level for: " + dir);
        }

        File top = new File(lines[0].trim());
        File dotGit;
        if (lines.length > 1 && !lines[1].isBlank()) {
            File gd = new File(lines[1].trim());
            dotGit = gd.isAbsolute() ? gd : new File(top, lines[1].trim());
        } else {
            dotGit = new File(top, ".git");
        }

        this.workTree = top;
        this.gitDir = dotGit;
        this.runner = new NativeGitRunner(top);
        this.open = true;
        LOGGER.info("NativeGitBackend opened: " + top.getAbsolutePath());
        return true;
    }

    public boolean isOpen() {
        return open;
    }

    public File getDirectory() {
        return open ? gitDir : null;
    }

    public File getWorkTree() {
        return open ? workTree : null;
    }

    /** See the note on the fields: the paths stay, and the runner refuses from now on. */
    public void close() {
        open = false;
        File last = workTree;
        if (last != null) {
            runner = NativeGitRunner.refusing(last, "the repository was closed");
        }
    }

    void ensureOpen() {
        if (!isOpen()) throw new IllegalStateException("Repository is not open");
    }

    /**
     * The ssh command that offers only the key of the profile active on this repository,
     * or {@code null} when the profile names none - see {@link SshKeys}.
     *
     * <p>Built on whatever ssh git would have run: {@code GIT_SSH_COMMAND}, then
     * {@code core.sshCommand}, then plain {@code ssh}. Handed to git in the environment
     * of the one command, so nothing is written to the repository's configuration.
     */
    private String profileSshCommand(java.util.function.Supplier<String> configuredSshCommand) {
        java.nio.file.Path key = SshKeys.keyFor(workTree);
        if (key == null) return null;
        return AskpassBridge.identitySshCommand(configuredSshCommand.get(), System.getenv(), key);
    }

    /**
     * {@code core.sshCommand}, read at most once and only if something asks: a network
     * command used to read it up to twice, each a git process of its own, and most of the
     * time nothing needs it at all.
     */
    private java.util.function.Supplier<String> configuredSshCommand(NativeGitRunner git) {
        return new java.util.function.Supplier<>() {
            private String value;
            private boolean read;

            @Override
            public String get() {
                if (!read) {
                    String configured = git.run("config", "--get", "core.sshCommand").trimmedStdout();
                    value = configured.isEmpty() ? null : configured;
                    read = true;
                }
                return value;
            }
        };
    }

    /**
     * The environment for a network command nobody is watching - see
     * {@link AskpassBridge#silentEnvironment} - with the profile's key, which applies to a
     * background command as much as to a pressed one. It goes in as the process's own
     * {@code GIT_SSH_COMMAND}, which is where the batch-mode options are then added to it.
     */
    Map<String, String> silentNetworkEnvironment(NativeGitRunner git) {
        var sshCommand = configuredSshCommand(git);
        Map<String, String> inherited = System.getenv();
        String identity = profileSshCommand(sshCommand);
        if (identity != null) {
            inherited = new HashMap<>(inherited);
            inherited.put("GIT_SSH_COMMAND", identity);
        }
        return AskpassBridge.silentEnvironment(sshCommand.get(), inherited);
    }

    /**
     * Runs a command that talks to a remote, so that anything git or ssh has to ask
     * reaches the user as a dialog - see {@link AskpassBridge}. Without the bridge (it
     * could not start here) this is exactly {@link NativeGitRunner#run}.
     *
     * <p>Windows' own OpenSSH cannot start the bridge's script. When that is what ran and
     * a prompt failed for it, the error says so and what to do instead of leaving the user
     * with ssh's "CreateProcessW failed error:193".
     */
    NativeGitRunner.Result runNetwork(List<String> args) {
        NativeGitRunner git = runner;
        var sshCommand = configuredSshCommand(git);
        String identity = profileSshCommand(sshCommand);
        Map<String, String> env = new HashMap<>(AskpassBridge.interactiveEnvironment(
                () -> identity != null ? identity : sshCommand.get()));
        if (identity != null) {
            env.put("GIT_SSH_COMMAND", identity);
        }
        return withConnectedAccount(withHelper -> networkFinished(git.runWithEnvironment(env, withHelper)), args);
    }

    /**
     * Runs a network command nobody is watching: every prompt off (see
     * {@link #silentNetworkEnvironment}), but the connected account and the user's stored
     * credentials still answer - which is what lets a background fetch of a private
     * repository work at all.
     */
    NativeGitRunner.Result runSilentNetwork(List<String> args) {
        NativeGitRunner git = runner;
        Map<String, String> env = new HashMap<>(silentNetworkEnvironment(git));
        env.putAll(AskpassBridge.silentCredentialEnvironment());
        return withConnectedAccount(withHelper -> git.runWithEnvironment(env, withHelper), args);
    }

    /**
     * Runs {@code args} with the application as git's credential helper (see
     * {@link AskpassBridge#credentialHelperArgs()}), and once more when it failed because
     * git refused the connected account.
     *
     * <p>git does not try another credential after a refusal within one process: it erases
     * the rejected one and exits. The erase marks the account rejected for git, so the
     * second run is answered by the user's own credentials - the fallback JGit gets within
     * a single operation. Fetch, pull, push, ls-remote and clone all fail on
     * authentication before transferring anything, so running one again repeats nothing.
     *
     * <p>The refusal count is global, so a refusal in a command running at the same moment
     * on the other lane can cost this one a second, needless attempt - at worst a repeat
     * of a failure, never a wrong answer.
     */
    static NativeGitRunner.Result withConnectedAccount(
            java.util.function.Function<List<String>, NativeGitRunner.Result> run, List<String> args) {
        List<String> withHelper = new java.util.ArrayList<>(AskpassBridge.credentialHelperArgs());
        withHelper.addAll(args);
        CredentialsManager credentials = CredentialsManager.getInstance();
        int refusedBefore = credentials.tokenRejections();
        NativeGitRunner.Result res = run.apply(withHelper);
        if (!res.isOk() && credentials.tokenRejections() != refusedBefore) {
            res = run.apply(withHelper);
        }
        if (!res.isOk()) {
            String hinted = credentials.withAccountHint(res.stderr());
            if (!hinted.equals(res.stderr())) {
                res = new NativeGitRunner.Result(res.exitCode(), res.stdoutBytes(), hinted);
            }
        }
        return res;
    }

    /**
     * The bookkeeping every interactive network command shares: the credential store
     * learns whether what it handed out worked, and a failure from Windows' own OpenSSH
     * gets the hint that says what to do about it.
     */
    static NativeGitRunner.Result networkFinished(NativeGitRunner.Result res) {
        CredentialsManager.getInstance().networkOperationFinished(res.isOk());
        String hint = res.isOk() ? null : AskpassBridge.nativeSshHint(res.stderr());
        if (hint != null) {
            res = new NativeGitRunner.Result(res.exitCode(), res.stdoutBytes(), res.stderr().stripTrailing() + "\n" + hint);
        }
        return res;
    }

    /** {@link #statusCommand}'s memory of the config file it last read, and what it found. */
    private volatile long tunedConfigStamp = Long.MIN_VALUE;
    private volatile boolean tuned;

    /**
     * Whether this repository has opted in to git's caches ({@link RepositoryTuning}).
     *
     * <p>Read from {@code .git/config} only when the file has changed - one stat per
     * status rather than one more git process - so turning the opt-in on or off in the
     * repository's settings takes effect at the next refresh, without reopening.
     */
    boolean tunedForSpeed() {
        File config = gitDir == null ? null : new File(gitDir, "config");
        long stamp = config == null ? 0 : config.lastModified() ^ config.length();
        if (stamp != tunedConfigStamp) {
            String value = configValues().get("core.untrackedcache");
            tuned = value != null && value.equalsIgnoreCase("true");
            tunedConfigStamp = stamp;
        }
        return tuned;
    }

    /**
     * Runs a {@code git status} with {@code args}, the way the repository's opt-in needs it.
     *
     * <h2>Why the opt-in changes how status is asked</h2>
     * Turning on the monitor and the untracked cache made no difference to this
     * application's own status - 83 ms before, 84 after, on 40,000 files - for two reasons
     * git documents. The staging list asks for {@code --untracked-files=all}, and git only
     * uses the untracked cache for that when {@code status.showUntrackedFiles} says "all"
     * too; and every status here ran with {@code GIT_OPTIONAL_LOCKS=0}, so git could never
     * save the monitor's progress or the cache into the index for the next call to use.
     *
     * <p>So in a repository that opted in, status says "all" in its config for this call
     * and may take the optional index lock, as a terminal's {@code git status} does. That
     * is the opt-in's trade: git briefly holds {@code index.lock} while it writes, which a
     * git command started at that same instant elsewhere would have to retry. Everywhere
     * else status keeps the no-lock, no-write behaviour.
     */
    NativeGitRunner.Result statusCommand(String... args) {
        List<String> command = new ArrayList<>();
        if (tunedForSpeed()) {
            command.add("-c");
            command.add("status.showUntrackedFiles=all");
            command.add("status");
            command.addAll(List.of(args));
            return runner.runWithEnvironment(Map.of("GIT_OPTIONAL_LOCKS", "1"), command);
        }
        command.add("status");
        command.addAll(List.of(args));
        return runner.run(command);
    }

    /** Every configuration value, read once: {@code git config -z --list}. */
    Map<String, String> configValues() {
        return CliParsers.parseConfigList(runner.run("config", "-z", "--list"));
    }

    /**
     * Every {@code --continue} and {@code --skip} runs with {@code GIT_EDITOR=true}, so
     * git takes the message it prepared rather than opening the user's
     * {@code core.editor} - a {@code code --wait} there held the task open until the
     * timeout killed git mid-rebase.
     */
    static final java.util.Map<String, String> NO_EDITOR = java.util.Map.of("GIT_EDITOR", "true");

    /**
     * Refuses a name git's option parser would read as a flag.
     *
     * <p>Every native command passes user data as argv and never through a shell, so
     * there is no quoting hole - but git parses a leading-dash positional as an option, so
     * a branch called {@code --orphan} would arrive as one. Where the porcelain accepts a
     * {@code --} separator (the branch and tag commands below) that closes the hole
     * outright. {@code git checkout <ref>} does not: everything after its {@code --} is a
     * path, not a revision. So the name is refused there, which is a clearer answer than
     * whatever git would say about the option it thought it saw.
     *
     * @return a failed result to return, or {@code null} when the value is fine
     */
    static GitResult rejectIfOptionLike(String value, String what) {
        return value != null && value.startsWith("-")
                ? GitResult.failed(what + " cannot start with \"-\": " + value)
                : null;
    }

    /**
     * Placed before a revision the UI did not validate, where {@code --} cannot go
     * because the command would read what follows it as paths (or reads none at all).
     * Everything after it is an argument, never an option: a branch named
     * {@code --abort} given to merge is a branch git cannot find, not an abort. git 2.24
     * and later; see {@link NativeGitRunner#MINIMUM_VERSION}.
     */
    static final String END_OF_OPTIONS = "--end-of-options";

    static String shorten(String hash) {
        return hash != null && hash.length() > 7 ? hash.substring(0, 7) : String.valueOf(hash);
    }

    /** The start points as {@code --stdin} wants them: one revision per line. */
    static byte[] revisionsInput(List<String> starts) {
        return (String.join("\n", starts) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * A history query the user asked for, failed - as an error rather than an empty list.
     *
     * <p>An empty answer reads as "no matches", "no history", "no blame": a search that
     * was killed at its time limit looked exactly like a search that found nothing. Thrown,
     * it reaches the caller's error handler with git's own words.
     */
    static GitOperationException queryFailed(String what, NativeGitRunner.Result res) {
        String why = res.stderr() == null || res.stderr().isBlank()
                ? "git exited with " + res.exitCode() : res.stderr();
        return new GitOperationException(what + " failed: " + why);
    }

    String configValue(String key) {
        var res = runner.run("config", "--get", key);
        return res.isOk() && !res.trimmedStdout().isBlank() ? res.trimmedStdout() : null;
    }
}
