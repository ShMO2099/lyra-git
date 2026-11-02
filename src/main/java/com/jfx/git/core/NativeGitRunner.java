package com.jfx.git.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes native {@code git} commands in a given working directory with low latency.
 *
 * <h2>What every invocation gets</h2>
 * Every git subprocess in the application is started here, so the settings that make
 * git's behaviour independent of the user's own configuration live here too, once,
 * rather than being remembered (or forgotten) by each caller:
 * <ul>
 *   <li>{@code -c core.quotepath=off}, so a path with non-ASCII characters is printed as
 *       itself rather than as a C-quoted octal escape a parser would have to undo;</li>
 *   <li>{@code -c log.showSignature=false}, so a user who verifies signatures in their
 *       terminal does not get gpg's report mixed into the output of every {@code log} and
 *       {@code show} parsed here - including {@code %B}, which amend pre-fills from;</li>
 *   <li>{@code -c diff.suppressBlankEmpty=false}, so a blank context line keeps the
 *       leading space the hunk parser counts lines by;</li>
 *   <li>{@code -c i18n.logOutputEncoding=UTF-8}, because every parser here decodes
 *       stdout as UTF-8, and a user with {@code i18n.commitEncoding=ISO-8859-1} (which
 *       {@code logOutputEncoding} defaults to) had every message, name and {@code %B}
 *       re-encoded into Latin-1 before it reached them;</li>
 *   <li>{@code -c color.ui=never} and {@code -c color.diff=never}, so a terminal setting
 *       of {@code always} cannot put escape sequences into output that is parsed. The
 *       command-specific key wins over {@code color.ui}, which is why the diff one, the
 *       output read as content, is pinned on its own;</li>
 *   <li>a C locale for messages and a UTF-8 one for character classes - see
 *       {@link #applyLocale};</li>
 *   <li>{@code GIT_EDITOR=true} (and the sequence editor), so a command that would open
 *       the user's {@code core.editor} - {@code rebase --continue}, a merge commit -
 *       accepts its default message instead of launching VS Code and waiting for it;</li>
 *   <li>{@code GIT_LITERAL_PATHSPECS=1}, so a path is a path: without it
 *       {@code [id].tsx} is a glob that also matches {@code i.tsx} and {@code d.tsx}, and
 *       discarding one file reverts others. See {@link #PATHSPEC_MAGIC} for the opt-out
 *       and {@link #HOOK_RUNNING_COMMANDS} for where it is deliberately not set.</li>
 * </ul>
 *
 * <h2>Timeouts and cancellation</h2>
 * Only commands that cannot write are held to a timeout. A command that writes is never
 * killed by the clock: killing {@code checkout} during a slow smudge filter left
 * {@code index.lock} behind and half the tree updated, and a commit whose pre-commit hook
 * or pinentry prompt ran past 30 seconds was killed mid-write. Those run until they
 * finish or the user cancels. Cancelling (and a timeout, where there is one) kills the
 * whole process tree, not just {@code git.exe}: hooks, filters and credential helpers
 * run as its children and otherwise outlive it.
 */
public final class NativeGitRunner {

    private static final Logger LOGGER = Logger.getLogger(NativeGitRunner.class.getName());

    /** Read-only local work: status, log, diff. Should be near-instant. */
    private static final int LOCAL_TIMEOUT_SECONDS = 30;

    /**
     * Readers whose cost grows with the length of history rather than the size of the
     * tree: a content search, a {@code --follow} walk, the blame of a long-lived file.
     *
     * <p>Thirty seconds is a hang detector for status and diff. For these it was a ceiling
     * a real answer could hit on a large repository, and an answer cut off there came back
     * as "no matches". They can still be cancelled at any time.
     */
    private static final int HISTORY_TIMEOUT_SECONDS = 5 * 60;

    /** See {@link #HISTORY_TIMEOUT_SECONDS}. */
    private static final Set<String> HISTORY_COMMANDS = Set.of("log", "blame", "rev-list", "shortlog");

    /**
     * Inherited variables that would point git somewhere other than this repository.
     *
     * <p>Exported by a hook, by {@code git rebase -x}, or by an IDE terminal the
     * application was launched from, each of these overrides the working directory and the
     * {@code -c} pins: started with {@code GIT_DIR} set, every command - writes included -
     * acted on that repository while the window showed this one. The {@code GIT_SSH*} and
     * askpass variables are left alone; those are deliberate, and set by this application.
     */
    static final Set<String> INHERITED_REPOSITORY_VARIABLES = Set.of(
            "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_COMMON_DIR", "GIT_NAMESPACE", "GIT_PREFIX",
            "GIT_CEILING_DIRECTORIES", "GIT_DISCOVERY_ACROSS_FILESYSTEM",
            "GIT_CONFIG", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT",
            "GIT_NO_REPLACE_OBJECTS", "GIT_REPLACE_REF_BASE", "GIT_GRAFT_FILE", "GIT_SHALLOW_FILE",
            "GIT_QUARANTINE_PATH", "GIT_REFLOG_ACTION");

    /**
     * Removes {@link #INHERITED_REPOSITORY_VARIABLES} and the numbered
     * {@code GIT_CONFIG_KEY_n}/{@code GIT_CONFIG_VALUE_n} pairs from {@code env}.
     */
    static void scrubInheritedRepositoryVariables(Map<String, String> env) {
        env.keySet().removeIf(name -> INHERITED_REPOSITORY_VARIABLES.contains(name)
                || name.startsWith("GIT_CONFIG_KEY_") || name.startsWith("GIT_CONFIG_VALUE_"));
    }

    /**
     * English messages, UTF-8 character classes.
     *
     * <p>Messages have to stay untranslated: callers read stderr for "CONFLICT", "would
     * clobber existing tag" and the like. That used to be {@code LC_ALL=C}, which also
     * set the character type to ASCII - and on Linux git then folds case for ASCII
     * letters only, so a case-insensitive search for "été" did not find "ÉTÉ" (checked
     * against git 2.43 on Ubuntu; Git for Windows matched either way). Only
     * {@code LC_CTYPE} is UTF-8 now. {@code LC_MESSAGES=C} keeps gettext untranslated -
     * it also ignores {@code LANGUAGE} for a C message locale, and that variable is
     * removed anyway - and every other inherited {@code LC_*} goes, so a user's
     * {@code LC_ALL} or {@code LC_TIME} cannot override either half.
     *
     * <p>The UTF-8 locale is the user's own when theirs is one - it certainly exists on
     * this machine - and otherwise {@code C.UTF-8}, or plain {@code UTF-8} on macOS, which
     * has no {@code C.UTF-8}. A name the system does not have would make git fall back to
     * C, as before, but would also make every Perl hook print a "Setting locale failed"
     * warning into the stderr shown to the user.
     */
    static void applyLocale(Map<String, String> env) {
        String ctype = utf8Ctype(env);
        env.keySet().removeIf(name -> name.startsWith("LC_") || name.equals("LANGUAGE"));
        env.put("LANG", "C");
        env.put("LC_CTYPE", ctype);
        env.put("LC_MESSAGES", "C");
    }

    /** The character-type locale for {@link #applyLocale}, from what {@code env} inherited. */
    private static String utf8Ctype(Map<String, String> env) {
        // The first of these that is set is the one in effect, as setlocale reads them.
        for (String name : List.of("LC_ALL", "LC_CTYPE", "LANG")) {
            String value = env.get(name);
            if (value == null || value.isBlank()) continue;
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".utf-8") || lower.endsWith(".utf8") || lower.equals("utf-8")) return value;
            break;
        }
        return MAC ? "UTF-8" : "C.UTF-8";
    }

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");

    /**
     * Anything that talks to a remote.
     *
     * <p>Fetch, push and ls-remote are bounded by the network and the size of the
     * repository, not by how fast git is. Holding them to the local timeout meant a large
     * fetch or a slow push was killed part-way through.
     */
    private static final int NETWORK_TIMEOUT_SECONDS = 15 * 60;

    /** {@link #timeoutFor} for a command that is only ever stopped by a cancel. */
    static final int NO_TIMEOUT = 0;

    /**
     * Subcommands that only read, and so are run with {@code GIT_OPTIONAL_LOCKS=0}.
     *
     * <p>{@code git status} (and several others) refresh the stat cache in
     * {@code .git/index} as a side effect, which takes the index lock. This application
     * runs those constantly in the background - every graph page, every refresh - and
     * often against a repository the user also has open in a terminal, or that another
     * engine in a hybrid tab is writing to. Git documents this variable for exactly this
     * case: a background client that wants an answer, not a tidier index.
     *
     * <p>Only subcommands that cannot write in any of their forms. {@code branch} and
     * {@code tag} are absent even though their listing forms read: telling one form from
     * the other means parsing their flags, and neither refreshes the index anyway, so
     * there is nothing to win for the risk.
     *
     * <p>These are also the commands a timeout may kill: killing a reader loses nothing.
     */
    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "status", "log", "show", "diff", "rev-list", "rev-parse", "for-each-ref",
            "cat-file", "ls-files", "ls-tree", "blame", "describe", "merge-base",
            "name-rev", "shortlog", "reflog", "check-ignore", "check-attr",
            "verify-commit", "count-objects", "diff-tree", "diff-index", "diff-files", "grep",
            // Writes objects but no ref, index or file: killing one loses nothing.
            "merge-tree");

    /**
     * Commands that run the user's hooks and are never given a pathspec by this
     * application, so {@code GIT_LITERAL_PATHSPECS} is left out of their environment.
     *
     * <p>The variable is inherited by every child git starts, hooks included, and a
     * pre-commit hook that runs {@code git diff --cached --name-only -- '*.py'} would
     * silently match nothing under it and check nothing. None of these commands takes a
     * path from this application, so there is nothing for the variable to protect there.
     * {@code checkout} is deliberately not listed: it does take paths from here (the
     * discard and restore fallbacks), so its {@code post-checkout} hook sees the variable.
     */
    private static final Set<String> HOOK_RUNNING_COMMANDS = Set.of(
            "commit", "merge", "rebase", "cherry-pick", "revert", "am", "pull", "push");

    /**
     * Pass as the environment to {@link #runWithEnvironment} to have git read pathspec
     * magic and globs again ({@code :(glob)}, {@code *.txt}) for one call. No caller
     * needs it today; it exists so the next one that does has a documented way to ask,
     * rather than reaching for a path that happens to contain no special characters.
     */
    public static final Map<String, String> PATHSPEC_MAGIC = Map.of("GIT_LITERAL_PATHSPECS", "0");

    /**
     * Configuration pinned on every invocation, placed before the subcommand.
     * See the class javadoc for why each one is here.
     */
    private static final List<String> CONFIG_OVERRIDES = List.of(
            "-c", "core.quotepath=off",
            "-c", "log.showSignature=false",
            "-c", "diff.suppressBlankEmpty=false",
            "-c", "i18n.logOutputEncoding=UTF-8",
            "-c", "color.ui=never",
            "-c", "color.diff=never");

    /** Global options that take their value as the following argument. */
    private static final Set<String> GLOBAL_OPTIONS_WITH_VALUE = Set.of(
            "-c", "-C", "--git-dir", "--work-tree", "--namespace", "--exec-path");

    /**
     * How often a running git process is checked against its task's cancel flag.
     *
     * <p>Short enough that Cancel feels immediate, long enough that a subsecond command -
     * which is most of them - still finishes in a single wait.
     */
    private static final long CANCEL_POLL_MILLIS = 200;

    /**
     * How long the output readers get to drain after git exits. The pipes are at EOF
     * the moment git and everything it started are gone, so this only runs out when a
     * hook left a background process holding them open.
     */
    private static final long READER_GRACE_MILLIS = 5000;

    /**
     * The oldest git this application drives.
     *
     * <p>Set by what the native engine actually runs, newest first:
     * <ul>
     *   <li>2.26: {@code rebase -i --empty=drop} (the interactive rebase) and
     *       {@code stash push --pathspec-from-file} (stashing selected files) - both
     *       options arrived with 2.26, and before it each of those operations failed
     *       outright with "unknown option";</li>
     *   <li>2.25: {@code sparse-checkout} itself, with {@code init --cone} and
     *       {@code set --stdin};</li>
     *   <li>2.24: {@code --end-of-options}, which keeps a revision the UI did not
     *       validate - a branch to merge, a commit to reset to - from being read as an
     *       option;</li>
     *   <li>2.23: {@code git restore}, which staging, unstaging and discarding use.</li>
     * </ul>
     * The minimum was 2.23 while the first two were already in use, so 2.23 to 2.25 were
     * offered an engine whose rebase and partial stash could not work. {@code git
     * maintenance} (2.29) and {@code init --initial-branch} (2.28) are newer still, and are
     * not relied on: housekeeping falls back to {@code gc --auto} by version, and a new
     * repository's branch is set with {@code symbolic-ref}. Below the minimum, the native
     * engines are not offered and the pure-Java engine is used instead, rather than
     * half-working.
     */
    static final int[] MINIMUM_VERSION = {2, 26};

    /** How long the version probe may take before it is abandoned (and asked again later). */
    private static final int PROBE_TIMEOUT_SECONDS = 15;

    /**
     * CreateProcess refuses a command line longer than 32767 characters. Checked here so
     * the user is told what happened rather than "The filename or extension is too long".
     */
    private static final int WINDOWS_COMMAND_LINE_LIMIT = 32_000;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    private static volatile Boolean gitInstalled = null;
    private static volatile String gitVersion = null;
    private static volatile String gitUnavailableReason = null;
    private static volatile Boolean gitLfsInstalled = null;
    private static volatile String gitLfsVersion = null;

    private final File workTree;

    /** Why this runner refuses every command, or {@code null} for an ordinary runner. */
    private final String refusal;

    public NativeGitRunner(File workTree) {
        this(workTree, null);
    }

    private NativeGitRunner(File workTree, String refusal) {
        this.workTree = workTree;
        this.refusal = refusal;
    }

    /**
     * A runner that runs nothing: every command fails at once with {@code why}.
     *
     * <p>What a closed backend leaves in place of its runner, so a call that was already
     * past its open check when the tab closed gets an ordinary failed result from git's
     * next step, rather than a {@link NullPointerException} from a field nulled under it.
     */
    static NativeGitRunner refusing(File workTree, String why) {
        return new NativeGitRunner(workTree, why);
    }

    public File getWorkTree() {
        return workTree;
    }

    /**
     * Whether a usable native {@code git} - on PATH, and at least {@link #MINIMUM_VERSION} -
     * is available.
     *
     * <p>Only a definite answer is remembered. A probe that times out (an antivirus
     * scanning git.exe on first start can take longer than any sensible bound) is
     * killed and answered "no" for now, but asked again next time, rather than leaving
     * the whole session without its native engines because of one slow start.
     */
    public static boolean isGitInstalled() {
        Boolean known = gitInstalled;
        if (known != null) return known;
        synchronized (NativeGitRunner.class) {
            if (gitInstalled != null) return gitInstalled;
            ProbeResult probe = probe("git", "--version");
            if (probe == null) {
                gitUnavailableReason = "git did not answer within " + PROBE_TIMEOUT_SECONDS + "s";
                return false;
            }
            if (!probe.ok()) {
                gitUnavailableReason = "git was not found on PATH";
                gitInstalled = false;
                return false;
            }
            gitVersion = probe.output();
            int[] version = parseVersion(gitVersion);
            if (version != null && compareVersions(version, MINIMUM_VERSION) < 0) {
                gitUnavailableReason = gitVersion + " is older than the minimum supported "
                        + MINIMUM_VERSION[0] + "." + MINIMUM_VERSION[1];
                LOGGER.warning("Native Git not used: " + gitUnavailableReason);
                gitInstalled = false;
                return false;
            }
            gitUnavailableReason = null;
            gitInstalled = true;
            LOGGER.info("Native Git detected: " + gitVersion);
            return true;
        }
    }

    /**
     * {@link #isGitInstalled()}'s answer if it has been settled, or {@code null} - for a
     * label on the FX thread, which must not be the one that runs the probe.
     */
    public static Boolean gitInstalledIfKnown() {
        return gitInstalled;
    }

    /**
     * Why {@link #isGitInstalled()} said no - not found, too old, or too slow to answer -
     * or {@code null} when it said yes.
     */
    public static String gitUnavailableReason() {
        return isGitInstalled() ? null : gitUnavailableReason;
    }

    /** Returns the detected native git version string (e.g. {@code "git version 2.45.1.windows.1"}). */
    public static String getGitVersion() {
        isGitInstalled();
        return gitVersion != null ? gitVersion : "Unknown";
    }

    /**
     * {@code [major, minor, patch]} from {@code git --version} output, or {@code null}
     * when the text carries no version. Vendor suffixes ({@code .windows.1},
     * {@code (Apple Git-146)}) are ignored.
     */
    static int[] parseVersion(String versionOutput) {
        if (versionOutput == null) return null;
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(versionOutput);
        if (!m.find()) return null;
        return new int[]{
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }

    /** Compares two versions component by component; missing components count as 0. */
    static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /**
     * Checks whether the {@code git-lfs} extension is installed - the binary being on
     * PATH at all, not whether its filters are registered anywhere (a separate,
     * per-repository question - see {@link NativeGitBackend#canMaterializeLfsContent}).
     * A machine can have {@code git-lfs} installed yet never have run {@code git lfs
     * install}, which leaves every LFS-tracked file a raw pointer on checkout despite
     * the binary being right there; this answers only the first half.
     */
    public static boolean isGitLfsInstalled() {
        Boolean known = gitLfsInstalled;
        if (known != null) return known;
        synchronized (NativeGitRunner.class) {
            if (gitLfsInstalled != null) return gitLfsInstalled;
            ProbeResult probe = probe("git", "lfs", "version");
            if (probe == null) return false;   // too slow this time; ask again later
            if (probe.ok()) gitLfsVersion = probe.output();
            gitLfsInstalled = probe.ok();
            return probe.ok();
        }
    }

    /** The detected {@code git-lfs} version string, or {@code "Unknown"} if not installed. */
    public static String getGitLfsVersion() {
        isGitLfsInstalled();
        return gitLfsVersion != null ? gitLfsVersion : "Unknown";
    }

    /**
     * Whether the LFS smudge filter is registered outside any particular repository -
     * system or global config, what {@code git lfs install} (without {@code --local})
     * registers. {@code git config --get} answers this correctly even run from outside
     * any repository, since with no {@code --local}/{@code --worktree} scope there is
     * no repo-specific config to consult.
     *
     * <p>Deliberately not cached like {@link #isGitLfsInstalled()}: the user can fix
     * this by running {@code git lfs install} in a terminal while the app is open, and
     * the point of a Preferences page showing it is to reflect that without asking for
     * a restart.
     */
    public static boolean isGitLfsFilterConfiguredGlobally() {
        ProbeResult probe = probe("git", "config", "--get", "filter.lfs.smudge");
        return probe != null && probe.ok();
    }

    /** A probe's outcome: exited 0 or not, and what it printed. */
    private record ProbeResult(boolean ok, String output) {
    }

    /**
     * Runs a short, fixed command outside any repository.
     *
     * @return the outcome, or {@code null} when it did not finish in time - in which case
     *         it has been killed, so a hung probe does not linger for the session
     */
    private static ProbeResult probe(String... command) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (command.length > 0 && "git".equals(command[0])) {
                GitExecutable.Resolved git = GitExecutable.get();
                builder.command().set(0, git.command());
                git.applyTo(builder.environment());
            }
            process = builder.start();
            // Drained concurrently: a probe that printed more than a pipe buffer would
            // otherwise block writing and look like a timeout.
            Process p = process;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> readStream(p.getInputStream(), out), "git-probe");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                destroyTree(process);
                LOGGER.warning(String.join(" ", command) + " did not answer within "
                        + PROBE_TIMEOUT_SECONDS + "s; will ask again later");
                return null;
            }
            reader.join(READER_GRACE_MILLIS);
            return new ProbeResult(process.exitValue() == 0,
                    out.toString(StandardCharsets.UTF_8).trim());
        } catch (InterruptedException e) {
            if (process != null) destroyTree(process);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, String.join(" ", command) + " could not be run", e);
            return new ProbeResult(false, "");
        }
    }

    /** Result of a native git process invocation. */
    public record Result(int exitCode, byte[] stdoutBytes, String stderr) {
        public boolean isOk() {
            return exitCode == 0;
        }

        public String stdout() {
            return new String(stdoutBytes, StandardCharsets.UTF_8);
        }

        public String trimmedStdout() {
            return stdout().trim();
        }
    }

    /** Executes a git command with string arguments and returns standard output/error. */
    public Result run(String... args) {
        return run(Arrays.asList(args));
    }

    /** Executes a git command and returns standard output/error. */
    public Result run(List<String> args) {
        return runWithInput(null, args);
    }

    /**
     * Executes a git command with extra environment variables.
     *
     * <p>Some git operations can only be steered through the environment — an interactive
     * rebase takes its plan from whatever {@code GIT_SEQUENCE_EDITOR} writes, with no flag
     * to supply one directly. The variables are applied after the runner's own defaults,
     * so a caller can override those deliberately (see {@link #PATHSPEC_MAGIC}).
     */
    public Result runWithEnvironment(Map<String, String> environment, List<String> args) {
        return execute(null, environment, args);
    }

    /**
     * Executes a git command, optionally passing binary input to stdin.
     *
     * <p>This is how free text reaches git: {@code commit -F -}, {@code tag -F -}. A
     * message on the command line is limited in length and, on Windows, at the mercy of
     * the JDK's quoting - see {@link #windowsArgument}.
     */
    public Result runWithInput(byte[] input, List<String> args) {
        return execute(input, Map.of(), args);
    }

    /**
     * Both of the above: stdin, and extra environment variables.
     *
     * <p>For a command that has to run with {@link #PATHSPEC_MAGIC} and takes its paths on
     * stdin ({@code --pathspec-from-file=-}), which is how a list of the user's files
     * reaches {@code stash push} without meeting the command-line length limit.
     */
    public Result runWithInputAndEnvironment(byte[] input, Map<String, String> environment,
                                             List<String> args) {
        return execute(input, environment, args, GitTaskRunner::isCancelled);
    }

    /**
     * {@link #runWithEnvironment}, stopped when {@code cancelled} turns true as well as
     * when the task it runs on is cancelled.
     *
     * <p>For work that is not a {@link GitTaskRunner} task, or whose caller was handed its
     * own cancel signal - a clone, whose API takes one, as the JGit engine's does.
     */
    Result runWithEnvironment(Map<String, String> environment, List<String> args,
                              java.util.function.BooleanSupplier cancelled) {
        return execute(null, environment, args,
                () -> GitTaskRunner.isCancelled() || cancelled.getAsBoolean());
    }

    private Result execute(byte[] input, Map<String, String> extraEnvironment,
                           List<String> args) {
        return execute(input, extraEnvironment, args, GitTaskRunner::isCancelled);
    }

    private Result execute(byte[] input, Map<String, String> extraEnvironment,
                           List<String> args, java.util.function.BooleanSupplier cancelled) {
        String subcommand = subcommandOf(args);
        String label = "git " + (subcommand == null ? "" : subcommand);
        if (refusal != null) {
            return new Result(-1, new byte[0], label + " was not run: " + refusal);
        }
        // Every git subprocess in the application goes through here, which makes this the
        // one place worth asking whether the caller is on the thread that draws. See
        // ThreadGuard: it reports by default and throws only under test.
        ThreadGuard.offTheFxThread(label);

        // Progress is asked for only when someone is watching: a transfer's stages and
        // percentages, parsed out of stderr as they arrive. Without a terminal git prints
        // none unless told to.
        java.util.function.Consumer<GitTaskRunner.Progress> reporter =
                subcommand != null && PROGRESS_COMMANDS.contains(subcommand)
                        ? GitTaskRunner.progressReporter() : null;
        boolean progressAdded = false;

        List<String> cmd = new ArrayList<>();
        // The real git rather than Git for Windows' launcher in front of it; see GitExecutable.
        GitExecutable.Resolved git = GitExecutable.get();
        cmd.add(git.command());
        cmd.addAll(CONFIG_OVERRIDES);
        int commandLineLength = "git".length();
        for (String arg : args) {
            String passed = arg;
            if (WINDOWS) {
                passed = windowsArgument(arg, jdkQuotesEmbeddedQuotes());
                if (passed == null) {
                    return new Result(-1, new byte[0], label + " was not run: the argument "
                            + abbreviate(arg) + " is wrapped in double quotes, which Windows"
                            + " cannot pass through to git intact");
                }
            }
            cmd.add(passed);
            commandLineLength += passed.length() + 3;
            if (reporter != null && !progressAdded && arg.equals(subcommand)) {
                cmd.add("--progress");
                progressAdded = true;
            }
        }
        if (WINDOWS && commandLineLength > WINDOWS_COMMAND_LINE_LIMIT) {
            return new Result(-1, new byte[0], label + " was not run: its command line would be "
                    + commandLineLength + " characters, more than Windows accepts");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workTree != null) {
            // Never run without the directory this runner was made for. Falling back to
            // the JVM's own working directory - which is a git repository whenever the
            // application was started from inside one - aimed a "discard all" at that
            // repository once this one's folder had been deleted, renamed or unmounted.
            if (!workTree.isDirectory()) {
                return new Result(-1, new byte[0], label + " was not run: the repository folder "
                        + workTree + " no longer exists");
            }
            pb.directory(workTree);
        }
        Map<String, String> env = pb.environment();
        scrubInheritedRepositoryVariables(env);
        // What the launcher would have set, now that it is not in the way.
        git.applyTo(env);
        applyLocale(env);
        env.put("GIT_PAGER", "cat");
        // Without this, an authenticated fetch with no cached credentials blocks on a
        // terminal prompt nobody can answer. Failing immediately gives the user an error
        // they can act on.
        env.put("GIT_TERMINAL_PROMPT", "0");
        // Nothing here can drive an editor. `true` accepts whatever git proposed, which
        // is what a GUI that already collected the message wants.
        env.put("GIT_EDITOR", "true");
        env.put("GIT_SEQUENCE_EDITOR", "true");
        if (subcommand == null || !HOOK_RUNNING_COMMANDS.contains(subcommand)) {
            env.put("GIT_LITERAL_PATHSPECS", "1");
        }
        // Read-only queries must not take the index lock; see READ_ONLY_COMMANDS.
        if (isReadOnly(args)) {
            env.put("GIT_OPTIONAL_LOCKS", "0");
        }
        // Applied last so a caller can deliberately override the defaults above.
        env.putAll(extraEnvironment);

        int timeoutSeconds = timeoutFor(args);

        Process process = null;
        try {
            process = pb.start();
            Process running = process;

            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();

            // The readers start before anything is written to stdin. Writing first would
            // deadlock as soon as git produced enough output to fill the pipe buffer:
            // git blocks writing its output, this thread blocks writing the input, and
            // neither side drains the other.
            Thread stdoutReader = new Thread(() -> readStream(running.getInputStream(), stdoutBuf),
                    "git-stdout");
            Thread stderrReader = new Thread(() -> {
                if (reporter == null) {
                    readStream(running.getErrorStream(), stderrBuf);
                } else {
                    ProgressFilter.copy(running.getErrorStream(), stderrBuf, reporter);
                }
            }, "git-stderr");
            stdoutReader.setDaemon(true);
            stderrReader.setDaemon(true);
            stdoutReader.start();
            stderrReader.start();

            try (var out = process.getOutputStream()) {
                if (input != null && input.length > 0) {
                    out.write(input);
                    out.flush();
                }
            } catch (IOException e) {
                // git can exit before reading all of stdin (a bad patch, say); its exit
                // code and stderr are the useful answer, not this broken pipe.
                LOGGER.log(Level.FINE, "git closed stdin early", e);
            }

            // Waited in slices rather than in one call so a cancelled task can kill the
            // process instead of holding the worker. A long fetch or rebase nobody is
            // waiting for any more should stop being run.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    destroyTree(process);
                    return new Result(-1, new byte[0], label + " was cancelled");
                }
                if (timeoutSeconds != NO_TIMEOUT && System.nanoTime() - deadline >= 0) {
                    destroyTree(process);
                    return new Result(-1, new byte[0],
                            label + " timed out after " + timeoutSeconds + "s");
                }
            }

            // The process has exited, so both pipes are at EOF once nothing else holds
            // them; the bound is only there so a stuck reader cannot hang the worker.
            stdoutReader.join(READER_GRACE_MILLIS);
            stderrReader.join(READER_GRACE_MILLIS);
            int exit = process.exitValue();
            if (stdoutReader.isAlive() || stderrReader.isAlive()) {
                // Something git started - a hook's background job, typically - still holds
                // the pipe. What has been read so far may be any prefix of the real output,
                // and parsing a prefix as the whole answer is how a truncated status or
                // log would pass for a real one. So this is a failure whatever git's own
                // exit code, which the message keeps so a user can tell what git did.
                return new Result(-1, new byte[0], label + " exited with " + exit
                        + ", but its output was still open " + (READER_GRACE_MILLIS / 1000)
                        + "s later (a hook or helper it started may still be running),"
                        + " so the result could not be read in full");
            }
            return new Result(exit, stdoutBuf.toByteArray(),
                    stderrBuf.toString(StandardCharsets.UTF_8).trim());
        } catch (InterruptedException interrupted) {
            // Being interrupted means someone asked this thread to stop - a shutdown, or
            // a lane forced down after its grace period. That is not a git failure, and
            // logging it at WARNING with a stack trace made an ordinary window close look
            // like a crash report. The process is killed rather than left running with
            // nobody waiting for it, and the flag is restored so anything above this
            // still sees the interrupt.
            if (process != null) destroyTree(process);
            Thread.currentThread().interrupt();
            return new Result(-1, new byte[0], label + " was interrupted");
        } catch (Exception e) {
            if (process != null) destroyTree(process);
            LOGGER.log(Level.WARNING, "Failed to run: " + label, e);
            return new Result(-1, new byte[0], e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /**
     * Kills {@code process} and everything it started.
     *
     * <p>The descendants are listed before the parent dies: once git is gone they are
     * orphans, and on Windows nothing then links them back to it. Killing only git left a
     * pre-commit hook's {@code sh.exe} running after Cancel.
     */
    static void destroyTree(Process process) {
        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants().toList();
        } catch (Exception e) {
            descendants = List.of();
        }
        process.destroyForcibly();
        for (ProcessHandle child : descendants) {
            try {
                child.destroyForcibly();
            } catch (Exception ignored) {
                // Already gone, or not ours to kill; nothing more can be done about it.
            }
        }
    }

    /** Whether this invocation only reads; see {@link #READ_ONLY_COMMANDS}. */
    private static boolean isReadOnly(List<String> args) {
        String subcommand = subcommandOf(args);
        return subcommand != null && READ_ONLY_COMMANDS.contains(subcommand);
    }

    /**
     * How long {@code args} may run before it is killed, or {@link #NO_TIMEOUT}.
     *
     * <p>Only commands that cannot leave a half-written repository behind have a budget:
     * readers, and the network commands whose local side is a ref update at the very end.
     * Everything else - commit, checkout, stash, reset, merge, rebase, pull, submodule
     * update, anything not recognised - runs until it finishes or is cancelled.
     */
    static int timeoutFor(List<String> args) {
        int at = subcommandIndex(args);
        if (at < 0) return LOCAL_TIMEOUT_SECONDS;   // `git --version` and the like
        String subcommand = args.get(at);
        if (HISTORY_COMMANDS.contains(subcommand)) return HISTORY_TIMEOUT_SECONDS;
        if (READ_ONLY_COMMANDS.contains(subcommand)) return LOCAL_TIMEOUT_SECONDS;
        String action = firstNonOption(args, at + 1);
        return switch (subcommand) {
            case "fetch", "push", "ls-remote" -> NETWORK_TIMEOUT_SECONDS;
            // A clone is a write from its first byte, and the one whose length has no
            // bound worth guessing: fifteen minutes is a large repository on a slow line.
            // Killed, it leaves a half-written folder git never gets to clean up. It runs
            // until it finishes or the user cancels, with a stall limit in its environment
            // (NativeGitBackend.cloneRepository) for a connection that goes quiet.
            case "clone" -> NO_TIMEOUT;
            // `remote` straddles the buckets: listing and get-url read local config,
            // update and prune (and show, unless -n) contact the network, and add,
            // remove, rename and set-url write config.
            case "remote" -> action == null || "get-url".equals(action)
                    ? LOCAL_TIMEOUT_SECONDS
                    : Set.of("update", "prune", "show").contains(action)
                            ? NETWORK_TIMEOUT_SECONDS
                            : NO_TIMEOUT;
            // Status walks every submodule and may take a while on a big tree, but only
            // reads; update, add, sync and deinit check out and write config.
            case "submodule" -> "status".equals(action) || "summary".equals(action)
                    ? NETWORK_TIMEOUT_SECONDS
                    : NO_TIMEOUT;
            case "stash", "worktree", "sparse-checkout" ->
                    "list".equals(action) ? LOCAL_TIMEOUT_SECONDS : NO_TIMEOUT;
            case "config" -> args.stream().anyMatch(a -> a.startsWith("--get") || a.equals("--list")
                    || a.equals("-l")) ? LOCAL_TIMEOUT_SECONDS : NO_TIMEOUT;
            default -> NO_TIMEOUT;
        };
    }

    /** The git subcommand {@code args} runs, skipping any leading global options. */
    static String subcommandOf(List<String> args) {
        int at = subcommandIndex(args);
        return at < 0 ? null : args.get(at);
    }

    private static int subcommandIndex(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("-")) return i;
            // `-c key=value` is two arguments; reading the value as the subcommand would
            // classify every call made with one.
            if (GLOBAL_OPTIONS_WITH_VALUE.contains(arg)) i++;
        }
        return -1;
    }

    /** The first argument from {@code from} that is not an option, or null. */
    private static String firstNonOption(List<String> args, int from) {
        for (int i = from; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("-")) return arg;
        }
        return null;
    }

    /**
     * Whether the JDK escapes a {@code "} inside an argument itself.
     *
     * <p>It does only when {@code jdk.lang.Process.allowAmbiguousCommands} is
     * {@code false}; by default ("legacy" mode) it wraps an argument containing a space in
     * quotes and leaves any quote inside it alone - so {@code Support 12" display} reached
     * git as two arguments, and {@code x" --author="Mallory} as an option. Read the same
     * way, on every call, as {@code java.lang.ProcessImpl} reads it.
     */
    private static boolean jdkQuotesEmbeddedQuotes() {
        return "false".equalsIgnoreCase(
                System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true"));
    }

    /**
     * The form of {@code arg} to hand to {@link ProcessBuilder} on Windows so that git
     * receives exactly {@code arg}.
     *
     * <p>An argument without a {@code "} is left to the JDK, which gets those right. One
     * with a {@code "} is, in the JDK's default mode, quoted here by the rules Windows
     * programs (git included) use to split their command line: wrapped in quotes, each
     * {@code "} escaped with a backslash, and backslashes doubled where they precede a
     * quote. The JDK passes an argument that already starts and ends with a quote through
     * untouched, so this is what git sees.
     *
     * <p>In the JDK's strict mode it does that escaping itself, except for an argument
     * that both starts and ends with a quote, which it either strips or rejects. There is
     * no form of such an argument that survives, so it is refused.
     *
     * @param jdkEscapes whether the JDK is in strict mode; see {@link #jdkQuotesEmbeddedQuotes}
     * @return the argument to pass, or {@code null} when it cannot be passed intact
     */
    static String windowsArgument(String arg, boolean jdkEscapes) {
        if (arg.indexOf('"') < 0) return arg;
        if (jdkEscapes) {
            boolean wrapped = arg.length() >= 2 && arg.startsWith("\"") && arg.endsWith("\"");
            return wrapped ? null : arg;
        }
        StringBuilder quoted = new StringBuilder(arg.length() + 8).append('"');
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"') {
                // Each backslash before a quote is doubled, then one more escapes the quote.
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
            } else {
                quoted.append("\\".repeat(backslashes)).append(c);
            }
            backslashes = 0;
        }
        // Trailing backslashes precede the closing quote, so they are doubled too.
        quoted.append("\\".repeat(backslashes * 2)).append('"');
        return quoted.toString();
    }

    private static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    /** The commands that transfer objects, and so report progress when asked to. */
    private static final java.util.Set<String> PROGRESS_COMMANDS = java.util.Set.of("fetch", "pull", "push", "clone");

    /**
     * Separates git's progress lines from the rest of its stderr.
     *
     * <p>Progress lines are reported and dropped; everything else is kept exactly as a run
     * without {@code --progress} would have produced it, because stderr is also where the
     * error messages that callers parse come from.
     */
    static final class ProgressFilter {
        private static final java.util.regex.Pattern PERCENT = java.util.regex.Pattern.compile(
                "^(?:remote: )?([A-Z][A-Za-z ]+?):\\s+(\\d{1,3})%");
        private static final java.util.regex.Pattern STAGE = java.util.regex.Pattern.compile(
                "^(?:remote: )?(Enumerating objects|Counting objects|Compressing objects|Receiving objects"
                        + "|Resolving deltas|Writing objects|Unpacking objects|Checking connectivity"
                        + "|Updating files|Filtering content|Delta compression)\\b");

        private ProgressFilter() {
        }

        static void copy(InputStream in, ByteArrayOutputStream out,
                         java.util.function.Consumer<GitTaskRunner.Progress> reporter) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    for (int i = 0; i < n; i++) {
                        byte b = buf[i];
                        if (b == '\r' || b == '\n') {
                            line(line.toString(StandardCharsets.UTF_8), out, reporter);
                            line.reset();
                        } else {
                            line.write(b);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            if (line.size() > 0) {
                line(line.toString(StandardCharsets.UTF_8), out, reporter);
            }
        }

        /** One line: a progress report, or text kept for the caller. */
        static void line(String text, ByteArrayOutputStream out,
                         java.util.function.Consumer<GitTaskRunner.Progress> reporter) {
            if (text.isEmpty()) return;
            java.util.regex.Matcher percent = PERCENT.matcher(text);
            if (percent.find()) {
                reporter.accept(new GitTaskRunner.Progress(percent.group(1),
                        Math.min(100, Integer.parseInt(percent.group(2))) / 100.0));
                return;
            }
            java.util.regex.Matcher stage = STAGE.matcher(text);
            if (stage.find()) {
                reporter.accept(new GitTaskRunner.Progress(stage.group(1), -1));
                return;
            }
            out.writeBytes((text + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void readStream(InputStream in, ByteArrayOutputStream out) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }
}
