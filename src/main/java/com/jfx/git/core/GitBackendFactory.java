package com.jfx.git.core;

import com.jfx.git.core.libgit2.Libgit2Backend;

import java.io.File;
import java.util.logging.Logger;

/**
 * Factory and configuration manager for {@link GitBackend} engines.
 */
public final class GitBackendFactory {

    private static final Logger LOGGER = Logger.getLogger(GitBackendFactory.class.getName());
    private static final String PREF_KEY_ENGINE = "git.engine";

    public enum BackendEngine {
        AUTO("Auto (Best Available)"),
        LIBGIT2("Bundled Libgit2 (Java FFM)"),
        NATIVE_CLI("Native Git CLI"),
        JGIT("Pure Java (JGit)");

        private final String label;

        BackendEngine(String label) {
            this.label = label;
        }

        /**
         * The name to show. Auto says what it resolves to on this machine: "Auto (Best
         * Available)" left the engine it picked - Hybrid, which is not itself in the menu -
         * for the user to guess.
         */
        public String label() {
            return this == AUTO ? autoLabel() : label;
        }
    }

    /** What {@link BackendEngine#AUTO} picks here, from what is already known. */
    static String autoLabel() {
        Boolean git = NativeGitRunner.gitInstalledIfKnown();
        if (git == null) return "Auto (Best Available)";
        if (git) return "Auto (Hybrid: git + JGit)";
        return Libgit2Backend.isAvailable() ? "Auto (libgit2)" : "Auto (JGit)";
    }

    private GitBackendFactory() {}

    /**
     * Gets the configured backend engine preference (default: {@link BackendEngine#AUTO}).
     */
    public static BackendEngine getEnginePreference() {
        String name = Settings.get(PREF_KEY_ENGINE, BackendEngine.AUTO.name());
        try {
            return BackendEngine.valueOf(name);
        } catch (Exception e) {
            return BackendEngine.AUTO;
        }
    }

    /**
     * Sets and persists the backend engine preference.
     */
    public static void setEnginePreference(BackendEngine engine) {
        if (engine == null) engine = BackendEngine.AUTO;
        Settings.put(PREF_KEY_ENGINE, engine.name());
        LOGGER.info("Git backend engine preference set to: " + engine);
    }

    /**
     * Checks if the given engine is available on the current machine.
     */
    public static boolean isEngineAvailable(BackendEngine engine) {
        return switch (engine) {
            case AUTO, JGIT -> true;
            case LIBGIT2 -> Libgit2Backend.isAvailable();
            case NATIVE_CLI -> NativeGitRunner.isGitInstalled();
        };
    }

    /**
     * Creates and opens a {@link GitBackend} for {@code dir} using the configured engine.
     */
    public static GitBackend create(File dir) throws GitOperationException {
        GitBackend backend = createDefault();
        backend.openRepository(dir);
        return backend;
    }

    /**
     * Creates an unopened {@link GitBackend} instance respecting the current engine preference.
     */
    public static GitBackend createDefault() {
        return createForEngine(getEnginePreference());
    }

    /**
     * Creates an unopened {@link GitBackend} instance for a specific {@link BackendEngine}.
     */
    public static GitBackend createForEngine(BackendEngine engine) {
        if (engine == null || engine == BackendEngine.AUTO) {
            return autoBackend();
        }

        return switch (engine) {
            case LIBGIT2 -> {
                if (Libgit2Backend.isAvailable()) {
                    yield new Libgit2Backend();
                }
                LOGGER.warning("Libgit2 requested but not available; falling back to AUTO");
                yield autoBackend();
            }
            case NATIVE_CLI -> {
                if (NativeGitRunner.isGitInstalled()) {
                    yield new NativeGitBackend();
                }
                LOGGER.warning("Native Git CLI requested but not installed; falling back to JGit");
                yield new GitService();
            }
            case JGIT -> new GitService();
            // Unreachable: the branch above returns for AUTO. Kept explicit so the switch
            // stays exhaustive without a default that would swallow a new constant.
            case AUTO -> autoBackend();
        };
    }

    // ------------------------------------------------------------------
    // Making a repository
    // ------------------------------------------------------------------

    /**
     * Whether a new repository is made by git rather than by JGit.
     *
     * <p>Every engine but pure JGit hands its writes to git when git is installed, so a
     * clone or an init does too. Before, both always ran JGit whatever the choice: a user
     * on the Native engine cloning an LFS repository got pointer files in place of the
     * content, no credential helper or ssh config of their own, and no post-checkout hook -
     * and then the tab opened on the native engine, over a checkout git had not made.
     */
    static boolean makesRepositoriesWithGit() {
        return getEnginePreference() != BackendEngine.JGIT && NativeGitRunner.isGitInstalled();
    }

    /** Clones with the engine the user chose; see {@link #makesRepositoriesWithGit()}. */
    public static GitResult cloneRepository(String uri, File dir, String branch, int depth,
                                            java.util.function.BooleanSupplier cancelled) {
        return makesRepositoriesWithGit()
                ? NativeGitBackend.cloneRepository(uri, dir, branch, depth, cancelled)
                : GitService.cloneRepository(uri, dir, branch, depth, cancelled);
    }

    /** Initialises with the engine the user chose; see {@link #makesRepositoriesWithGit()}. */
    public static GitResult initRepository(File dir, RepoInitTemplates templates) {
        return makesRepositoriesWithGit()
                ? NativeGitBackend.initRepository(dir, templates)
                : GitService.initRepository(dir, templates);
    }

    /**
     * Settles which engines this machine has, off the calling thread.
     *
     * <p>Both answers are cached once known, and both cost something the first time:
     * libgit2 is hashed, possibly extracted, and bound; native git is a {@code git
     * --version} process. Started at launch, the first tab's {@link #createDefault()}
     * finds them ready instead of working them out on the FX thread.
     */
    public static void warmUp() {
        Thread thread = new Thread(() -> {
            try {
                // The order autoBackend asks in: libgit2 is only consulted without git.
                if (!NativeGitRunner.isGitInstalled()) {
                    Libgit2Backend.isAvailable();
                }
            } catch (RuntimeException | LinkageError e) {
                // Only a head start: the first real use detects again and reports properly.
                LOGGER.fine("Engine warm-up failed: " + e);
            }
        }, "engine-warm-up");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The best engine present on this machine.
     *
     * <h2>Hybrid first, not libgit2</h2>
     * libgit2 was preferred on the assumption that an in-process status scan beats a
     * subprocess. Measured, it does not: on a 40,000-file repository with 50 changed
     * paths, {@code getStagingState} took a median 79 ms on the Hybrid engine
     * ({@code git status} with its multi-threaded index refresh), 83 ms on the CLI alone,
     * 407 ms on libgit2 and 692 ms on JGit. libgit2 also served three methods while the
     * rest went to the same CLI and JGit pair Hybrid uses, with known divergences of its
     * own. So it is the choice only where git is not installed, where it still beats JGit.
     */
    private static GitBackend autoBackend() {
        if (NativeGitRunner.isGitInstalled()) {
            LOGGER.info("Using HybridGitBackend (native git: " + NativeGitRunner.getGitVersion() + ")");
            return new HybridGitBackend();
        }
        if (Libgit2Backend.isAvailable()) {
            LOGGER.info("No native git; using Libgit2Backend (bundled libgit2 via Java FFM)");
            return new Libgit2Backend();
        }
        LOGGER.info("No native engine available; using pure JGit");
        return new GitService();
    }

    /**
     * A short label for the engine actually doing the work.
     *
     * <p>Unwraps {@link SwitchableGitBackend} so the badge names the engine rather than
     * the indirection the UI holds.
     */
    /**
     * Which of the concrete engines {@code backend} actually is, or {@code null} when it
     * is something else entirely.
     *
     * <p>{@link BackendEngine#AUTO} is never returned: it is a preference meaning "pick
     * one", not an engine anything can be. A caller comparing a stored preference against
     * this therefore has to resolve AUTO first, or accept that AUTO never matches.
     */
    public static BackendEngine engineOf(GitBackend backend) {
        if (backend instanceof SwitchableGitBackend switchable) {
            return engineOf(switchable.currentEngine());
        }
        if (backend instanceof Libgit2Backend) return BackendEngine.LIBGIT2;
        if (backend instanceof NativeGitBackend) return BackendEngine.NATIVE_CLI;
        if (backend instanceof GitService) return BackendEngine.JGIT;
        return null;
    }

    public static String describeBackend(GitBackend backend) {
        if (backend == null) return "None";
        if (backend instanceof SwitchableGitBackend switchable) {
            return describeBackend(switchable.currentEngine());
        }
        if (backend instanceof Libgit2Backend) {
            // Status and staging run in-process; history and transport delegate. Saying
            // so keeps the badge from claiming a purity the engine does not have.
            return "Libgit2 (partial)";
        }
        if (backend instanceof HybridGitBackend) {
            return "Hybrid Git (" + NativeGitRunner.getGitVersion() + ")";
        }
        if (backend instanceof NativeGitBackend) {
            return "Native Git (" + NativeGitRunner.getGitVersion() + ")";
        }
        if (backend instanceof GitService) {
            return "JGit (Java)";
        }
        return backend.getClass().getSimpleName();
    }

    /** Longer text for the badge tooltip, explaining what the engine does and does not do. */
    public static String describeBackendDetail(GitBackend backend) {
        if (backend instanceof SwitchableGitBackend switchable) {
            return describeBackendDetail(switchable.currentEngine());
        }
        if (backend instanceof Libgit2Backend) {
            return """
                   Bundled libgit2 through the Java FFM API.
                   In-process: status, staging, unstaging, current branch.
                   Delegated: history, diffs, network, rebase.
                   Click to change engine.""";
        }
        if (backend instanceof HybridGitBackend) {
            return "Native git for status and every change, JGit for history and diffs.\n"
                    + NativeGitRunner.getGitVersion() + "\nClick to change engine.";
        }
        if (backend instanceof NativeGitBackend) {
            return "Every operation shells out to git.\n"
                    + NativeGitRunner.getGitVersion() + "\nClick to change engine.";
        }
        if (backend instanceof GitService) {
            return "Pure Java. No native git required.\nClick to change engine.";
        }
        return "Click to change engine.";
    }
}
