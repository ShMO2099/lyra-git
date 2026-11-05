package com.jfx.git.testsupport;

// Its own package, not com.jfx.git: module-info exports that one, and a public JUnit
// extension there puts JUnit's ExtensionContext on the module's exported API.

import com.jfx.git.core.ThreadGuard;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Keeps JGit from holding a test's repository open, or still working in it, after the
 * test has finished.
 *
 * <h2>The failure this prevents</h2>
 * A test that passed, and then failed in teardown:
 *
 * <pre>Failed to delete temp directory … .git/modules/libs/sub/objects/pack/pack-….pack</pre>
 *
 * The assertions had already succeeded; {@code @TempDir} could not delete the directory
 * because something still had the pack file open. On Windows that is fatal to the
 * cleanup, and it comes and goes with execution order - so it reads as a random failure
 * in whichever test happened to run last.
 *
 * <h2>What actually holds it, which is not what it looks like</h2>
 * The obvious suspect is memory-mapped pack access, which {@code LyraApp.start()}
 * disables on Windows and tests never run. It is not that: setting
 * {@code packedGitMMAP=false} at class-init changes nothing here, because the handles are
 * held by two other things.
 *
 * <ul>
 *   <li><b>Cached repositories.</b> JGit's {@link RepositoryCache} hands out shared
 *       {@code Repository} instances - a submodule walk opens one per submodule - and
 *       closing the outer repository does not close those.</li>
 *   <li><b>Pack windows.</b> Even once a repository is closed, its already-open pack
 *       windows live in JGit's global window cache. Re-installing a
 *       {@link WindowCacheConfig} is what drops them; installing one at startup does
 *       nothing for windows opened afterwards.</li>
 * </ul>
 *
 * <p>So both happen after every test. Several test classes already did this by hand for
 * exactly this reason; doing it here means the ones that did not are covered too, and a
 * new test does not have to know about any of it.
 *
 * <h2>The same failure on Linux, from a different cause</h2>
 * <pre>Failed to delete temp directory … .git/gc.log.lock</pre>
 * Nothing holds a file open there; JGit is still at work in the folder. See
 * {@link #runJGitsAutomaticGcInTheForeground}.
 *
 * <h2>How it is registered</h2>
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} plus
 * {@code junit.jupiter.extensions.autodetection.enabled} in
 * {@code junit-platform.properties}.
 */
public final class TestEnvironment implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    private static volatile boolean configured;

    /**
     * Matches what the application does at startup on Windows: no memory-mapped packs, so
     * a pack file this process reads is not one the user's own git cannot write. And has
     * JGit finish its housekeeping before a command returns.
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        if (configured) return;
        configured = true;
        releasePackFiles();
        runJGitsAutomaticGcInTheForeground();
    }

    /**
     * Sets {@code gc.autoDetach=false} beneath every configuration JGit reads.
     *
     * <h2>Why</h2>
     * A JGit merge, rebase or fetch ends with an automatic gc, and with {@code gc.autoDetach}
     * at its default of {@code true} that gc runs in the background: {@code gc.log.lock} is
     * created before the command returns, and deleted later by a thread of JGit's own
     * {@code WorkQueue}, which on a busy machine is after the test has finished and while
     * {@code @TempDir} is deleting the folder. A file that disappears between JUnit listing
     * a directory and deleting it fails the cleanup as surely as one that will not go - a
     * passing test reported as an error, on whichever test happened to end with a merge. In
     * the foreground the gc is done, lock and all, before the command returns; a repository
     * this small never needs one, so it costs a glance at the object directory.
     *
     * <h2>Why beneath everything</h2>
     * A repository's config inherits the user's, which inherits the system's, which
     * inherits JGit's own file. The setting goes in memory under that last one, the only
     * layer nothing in the suite replaces: tests that keep JGit away from the developer's
     * {@code ~/.gitconfig} swap the user and system layers, delegating the rest to the
     * reader installed here. Saving a config writes only its own entries, so the setting
     * reaches no file - not when the application saves the user's config, and not when
     * JGit records what it measured about the file system in its own. A machine whose
     * config sets {@code gc.autoDetach} itself still wins, as it does for git.
     */
    private static void runJGitsAutomaticGcInTheForeground() {
        SystemReader.setInstance(new SystemReader.Delegate(SystemReader.getInstance()) {
            @Override
            public FileBasedConfig openJGitConfig(Config parent, FS fs) {
                Config suiteDefaults = new Config(parent);
                suiteDefaults.setBoolean(ConfigConstants.CONFIG_GC_SECTION, null,
                        ConfigConstants.CONFIG_KEY_AUTODETACH, false);
                return super.openJGitConfig(suiteDefaults, fs);
            }
        });
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // Violations only: warn-once suppression is deliberately kept across the run.
        ThreadGuard.clearViolations();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        // Order matters: close the cached repositories first, then drop the windows their
        // packs left behind. The other way round leaves the windows a closing repository
        // re-opens nothing to purge.
        RepositoryCache.clear();
        releasePackFiles();
        failIfTheApplicationRanGitOnTheFxThread(context);
    }

    /**
     * Fails a test whose <em>application</em> code spawned git on the JavaFX thread.
     *
     * <h2>Why this is here and not in one test</h2>
     * {@link ThreadGuard} was added as a warn-once logger and did its job immediately: a
     * fully green suite was quietly reporting that {@code git diff-tree},
     * {@code git branch} and {@code git for-each-ref} had run on the thread that draws.
     * Nothing failed, so nothing was read, and the warnings sat in the log for four
     * rounds. A tripwire nobody has wired to a bell is decoration.
     *
     * <p>Checking here rather than in a single aggregate test means the failure lands on
     * the test that caused it, naming the call site, and covers every path any test
     * touches - including ones written later by someone who has never heard of the
     * invariant. That is the difference between documenting a rule and enforcing one.
     *
     * <p>Every engine reaches this: the guard sits at the subprocess spawn, and also at
     * {@link com.jfx.git.core.SwitchableGitBackend}, which every call the UI makes into a
     * repository passes through - so a JGit read on the FX thread, which spawns nothing,
     * is caught as well. That second guard also catches an engine called from another
     * repository's lane, which fails the test the same way.
     */
    private static void failIfTheApplicationRanGitOnTheFxThread(ExtensionContext context) {
        var violations = ThreadGuard.productViolations();
        ThreadGuard.clearViolations();
        if (violations.isEmpty()) {
            return;
        }
        StringBuilder detail = new StringBuilder(
                "git ran on the wrong thread during "
                        + context.getDisplayName() + "\n"
                        + "On the JavaFX Application Thread the UI is frozen for the whole of"
                        + " each of these: move the call into the surrounding GitTaskRunner"
                        + " supplier, or read the value the model already carries. On another"
                        + " repository's lane it runs alongside that repository's own work:"
                        + " submit it naming the repository it touches.\n");
        for (ThreadGuard.Violation violation : violations) {
            detail.append("  - ").append(violation.operation())
                    .append("  from  ").append(violation.caller())
                    .append("  on  ").append(violation.where()).append('\n');
        }
        detail.append("If this is fixture setup rather than the application freezing"
                + " itself, wrap it in ThreadGuard.buildingFixture().");
        throw new AssertionError(detail.toString());
    }

    /** Re-installing the config is JGit's way of dropping every open pack window. */
    private static void releasePackFiles() {
        WindowCacheConfig config = new WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }
}
