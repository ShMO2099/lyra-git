package com.jfx.git.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The opt-in "Speed up this repository": git's own caches for a large working tree and a
 * long history, switched on in the repository's config.
 *
 * <h2>What it turns on, and what it leaves alone</h2>
 * <ul>
 *   <li>{@code core.fsmonitor} - git's built-in file-system monitor (Windows and macOS,
 *       git 2.37 and later). A background daemon watches the working tree, so a status
 *       re-checks only what changed since the last one instead of every file. With the
 *       cache below, the application's status scan on a 40,000-file repository went from
 *       84 ms to 51 ms on a warm disk; on larger or colder trees git's own figures are far
 *       bigger. It only does that because status is asked to match - see
 *       {@code CliRepo.statusCommand}; asked as before, the same settings changed
 *       nothing.</li>
 *   <li>{@code core.untrackedCache} - remembers which folders hold untracked files, for
 *       the same reason, and pairs with the monitor.</li>
 *   <li>A commit-graph with changed-path filters, written once now and kept up to date by
 *       {@code fetch.writeCommitGraph}. git then walks history from the graph file rather
 *       than by unpacking commits: on a 20,000-commit history a file's history went from
 *       159 ms to 119 ms and "is this commit pushed?" from 155 ms to 104 ms.</li>
 * </ul>
 * Not {@code feature.manyFiles}, though it is git's own bundle for this: it also sets
 * {@code index.skipHash}, which writes the index without a checksum. JGit reads that, but
 * libgit2 and git older than 2.13 refuse such an index, and this application can open a
 * repository with either.
 *
 * <p>Opt-in because it changes the repository's own config - which the user's terminal git
 * then follows too - and starts a background process per repository. Every step is git's,
 * so it needs git installed; it helps the engines that run git (Auto and Native), and does
 * nothing for pure JGit, which uses none of these.
 */
public final class RepositoryTuning {

    /** The first git with the built-in monitor on Windows and macOS. */
    private static final int[] FSMONITOR_VERSION = {2, 37};

    /**
     * What is switched on in this repository.
     *
     * @param available        whether git is here to do any of it
     * @param fileSystemMonitor {@code core.fsmonitor} is on
     * @param untrackedCache   {@code core.untrackedCache} is on
     * @param commitGraph      {@code fetch.writeCommitGraph} is on
     */
    public record State(boolean available, boolean fileSystemMonitor, boolean untrackedCache,
                        boolean commitGraph) {
        /** On, as far as this opt-in is concerned: what it would set is set. */
        public boolean enabled() {
            return untrackedCache && commitGraph && (fileSystemMonitor || !fileSystemMonitorSupported());
        }
    }

    private RepositoryTuning() {
    }

    /** Whether this machine's git has the built-in file-system monitor. */
    public static boolean fileSystemMonitorSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!(os.startsWith("windows") || os.contains("mac"))) return false;
        if (!NativeGitRunner.isGitInstalled()) return false;
        int[] version = NativeGitRunner.parseVersion(NativeGitRunner.getGitVersion());
        return version != null && NativeGitRunner.compareVersions(version, FSMONITOR_VERSION) >= 0;
    }

    /** Reads what is on. Runs git; call it off the FX thread. */
    public static State read(File workTree) {
        if (workTree == null || !NativeGitRunner.isGitInstalled()) {
            return new State(false, false, false, false);
        }
        NativeGitRunner git = new NativeGitRunner(workTree);
        return new State(true,
                isTrue(git, "core.fsmonitor"),
                isTrue(git, "core.untrackedCache"),
                isTrue(git, "fetch.writeCommitGraph"));
    }

    /**
     * Switches it all on. The commit-graph is written here, once, which on a very long
     * history takes a while; after that each fetch adds to it.
     */
    public static GitResult enable(File workTree) {
        if (workTree == null || !NativeGitRunner.isGitInstalled()) {
            return GitResult.failed("Speeding up a repository needs git installed");
        }
        NativeGitRunner git = new NativeGitRunner(workTree);
        List<String> done = new ArrayList<>();
        GitResult step = set(git, "core.untrackedCache", "true");
        if (!step.isOk()) return step;
        done.add("the untracked-file cache");
        if (fileSystemMonitorSupported()) {
            step = set(git, "core.fsmonitor", "true");
            if (!step.isOk()) return step;
            done.add("the file-system monitor");
        }
        step = set(git, "fetch.writeCommitGraph", "true");
        if (!step.isOk()) return step;
        // --changed-paths so a file's history can skip commits that did not touch it.
        // Later writes - on fetch, by gc - keep the filters once a graph has them.
        var written = git.run("commit-graph", "write", "--reachable", "--changed-paths");
        if (!written.isOk()) {
            return GitResult.failed("Could not write the commit-graph: " + written.stderr());
        }
        done.add("a commit-graph");
        return GitResult.ok("Turned on " + String.join(", ", done));
    }

    /**
     * Switches it all off: stops the monitor's daemon and removes the settings. The
     * commit-graph file stays - git keeps using it, and it does no harm.
     */
    public static GitResult disable(File workTree) {
        if (workTree == null || !NativeGitRunner.isGitInstalled()) {
            return GitResult.failed("Changing this needs git installed");
        }
        NativeGitRunner git = new NativeGitRunner(workTree);
        // Stopped before the setting goes, so nothing is left watching a repository whose
        // config no longer asks for it. Failure is fine: it may never have started.
        git.run("fsmonitor--daemon", "stop");
        for (String key : List.of("core.fsmonitor", "core.untrackedCache", "fetch.writeCommitGraph")) {
            var unset = git.run("config", "--local", "--unset", key);
            // Exit 5: the key was not set, which is the state being asked for.
            if (!unset.isOk() && unset.exitCode() != 5) {
                return GitResult.failed("Could not unset " + key + ": " + unset.stderr());
            }
        }
        return GitResult.ok("Turned off the file-system monitor and git's caches for this repository");
    }

    private static boolean isTrue(NativeGitRunner git, String key) {
        var res = git.run("config", "--local", "--type=bool", "--get", key);
        return res.isOk() && "true".equals(res.trimmedStdout());
    }

    private static GitResult set(NativeGitRunner git, String key, String value) {
        var res = git.run("config", "--local", key, value);
        return res.isOk() ? GitResult.ok() : GitResult.failed("Could not set " + key + ": " + res.stderr());
    }
}
