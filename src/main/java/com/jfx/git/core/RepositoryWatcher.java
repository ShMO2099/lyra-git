package com.jfx.git.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Notices a repository changing on disk while this application is not the one changing it.
 *
 * <h2>Why, on top of the focus check</h2>
 * The tab already re-checks the repository when the window regains focus. That misses
 * the case where the window never lost it: a terminal in a split screen, an IDE on the
 * other monitor, a formatter run by a file watcher of its own. Sourcegit and
 * gitfourchette both watch the file system for this reason.
 *
 * <h2>What it deliberately ignores</h2>
 * The hard part of a watcher is not hearing things, it is not hearing too much:
 * <ul>
 *   <li><b>Git's own churn.</b> {@code *.lock} files, {@code ORIG_HEAD},
 *       {@code FETCH_HEAD}, the object database and reflogs change on every operation
 *       and say nothing a refresh would show.</li>
 *   <li><b>This application's own work.</b> A commit here writes the index, HEAD and
 *       reflog like any other; see {@link GitTaskRunner#repositoryWorkWithin}. Events
 *       that settle while repository work is running, or just after, are dropped - the
 *       operation refreshes the tab itself.</li>
 *   <li><b>Heavy ignored directories.</b> {@code node_modules}, and any plain directory
 *       name the root {@code .gitignore} lists, are skipped when cheap to tell.</li>
 * </ul>
 * And it does not refresh anything itself. It only says "look again"; the tab's own
 * snapshot comparison decides whether anything visible changed. So a false alarm costs
 * one status scan, never a redraw - and bursts are debounced into one.
 */
public final class RepositoryWatcher implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RepositoryWatcher.class.getName());

    /** How long the file system has to be quiet before one "look again" is sent. */
    static final long DEFAULT_DEBOUNCE_MILLIS = 750;

    /** Events that settle this soon after the application's own repository work are its own. */
    static final long OWN_WORK_QUIET_MILLIS = 1500;

    /**
     * Directories registered one by one where the platform cannot watch a tree. Linux
     * inotify watches are a per-user resource; past this the watcher covers the top of the
     * tree only, and the focus check covers the rest.
     */
    private static final int MAX_DIRECTORIES = 2000;

    /** Git directory entries whose changes never show in anything the tab draws. */
    private static final Set<String> GIT_NOISE = Set.of(
            "objects", "logs", "lfs", "hooks", "modules", "worktrees",
            "ORIG_HEAD", "FETCH_HEAD", "COMMIT_EDITMSG", "gc.pid", "gc.log", "description");

    private static final Set<String> ALWAYS_SKIPPED = Set.of("node_modules");

    private final Path workTree;
    private final Path gitDir;
    private final Runnable onChange;
    private final BooleanSupplier ownWorkInProgress;
    private final long debounceMillis;
    private final WatchService service;
    private final ScheduledExecutorService timer;
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private final Set<String> ignoredNamesAnywhere = new HashSet<>();
    private final Set<String> ignoredNamesAtRoot = new HashSet<>();
    private final boolean wholeTree;
    private final Thread thread;
    private ScheduledFuture<?> pending;
    private volatile boolean closed;

    /**
     * Starts watching, or returns {@code null} when the platform cannot - in which case
     * the focus check is still there, so this is an improvement that can be absent.
     *
     * @param repository the backend the application works on this repository through, so
     *                   its own work - on this repository's lane, not another tab's - is
     *                   told apart from someone else's
     * @param onChange   called on a background thread when something outside this
     *                   application may have changed the repository
     */
    public static RepositoryWatcher start(GitBackend repository, File workTree, File gitDir,
                                          Runnable onChange) {
        return start(workTree, gitDir, onChange,
                () -> GitTaskRunner.repositoryWorkWithin(repository, OWN_WORK_QUIET_MILLIS),
                DEFAULT_DEBOUNCE_MILLIS);
    }

    static RepositoryWatcher start(File workTree, File gitDir, Runnable onChange,
                                   BooleanSupplier ownWorkInProgress, long debounceMillis) {
        if (workTree == null || !workTree.isDirectory()) return null;
        try {
            return new RepositoryWatcher(workTree.toPath(), gitDir == null ? null : gitDir.toPath(),
                    onChange, ownWorkInProgress, debounceMillis);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.INFO, "Not watching " + workTree + " for changes", e);
            return null;
        }
    }

    private RepositoryWatcher(Path workTree, Path gitDir, Runnable onChange,
                              BooleanSupplier ownWorkInProgress, long debounceMillis) throws IOException {
        this.workTree = workTree.toAbsolutePath().normalize();
        this.gitDir = gitDir == null ? this.workTree.resolve(".git") : gitDir.toAbsolutePath().normalize();
        this.onChange = onChange;
        this.ownWorkInProgress = ownWorkInProgress;
        this.debounceMillis = debounceMillis;
        readRootIgnores();

        this.service = FileSystems.getDefault().newWatchService();
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "repo-watch-debounce");
            t.setDaemon(true);
            return t;
        });

        this.fileTree = fileTreeModifier();
        this.wholeTree = fileTree != null;

        // Registration happens on the watcher's own thread, not the caller's. Where the
        // platform cannot watch a tree (Linux) it is a walk of up to MAX_DIRECTORIES
        // directories with an inotify registration each, and the caller is a tab that
        // has just opened - on the FX thread.
        this.thread = new Thread(this::run, "repo-watch-" + this.workTree.getFileName());
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Counted down once registration has finished, or given up. */
    private final java.util.concurrent.CountDownLatch registered = new java.util.concurrent.CountDownLatch(1);

    /** {@code ExtendedWatchEventModifier.FILE_TREE} where the platform has it, else null. */
    private final WatchEvent.Modifier fileTree;

    /**
     * Registers what is watched. Changes made while this runs may go unheard, exactly as
     * they did while the caller's thread was doing it: the tab reads the repository as it
     * opens, and the focus check covers the gap.
     *
     * @return false when the platform refused, and nothing is being watched
     */
    private boolean registerAll() {
        try {
            if (wholeTree) {
                // One handle for the whole tree (Windows). Registering directory by
                // directory there would hold a handle open on each one, which stops the
                // user renaming any folder in their own repository.
                register(workTree, fileTree);
                if (!gitDir.startsWith(workTree) && Files.isDirectory(gitDir)) {
                    register(gitDir, fileTree);
                }
            } else {
                registerTree(workTree);
                if (Files.isDirectory(gitDir)) {
                    register(gitDir, null);
                    Path refs = gitDir.resolve("refs");
                    if (Files.isDirectory(refs)) registerTree(refs);
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            if (!closed) {
                LOGGER.log(Level.INFO, "Not watching " + workTree + " for changes", e);
            }
            closeQuietly();
            return false;
        } finally {
            registered.countDown();
        }
    }

    /** For tests: waits until the registrations {@link #start} began are in place. */
    boolean awaitRegistration(long millis) throws InterruptedException {
        return registered.await(millis, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    private static final WatchEvent.Kind<?>[] KINDS = {
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY};

    /**
     * {@code com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE}, which only the
     * Windows watch service honours. Looked up reflectively: it lives in
     * {@code jdk.unsupported}, which this module does not otherwise need.
     */
    private static WatchEvent.Modifier fileTreeModifier() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        try {
            Class<?> type = Class.forName("com.sun.nio.file.ExtendedWatchEventModifier");
            for (Object constant : type.getEnumConstants()) {
                if ("FILE_TREE".equals(((Enum<?>) constant).name())) {
                    return (WatchEvent.Modifier) constant;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "Recursive watching is unavailable", e);
        }
        return null;
    }

    private void register(Path dir, WatchEvent.Modifier modifier) throws IOException {
        WatchKey key = modifier == null
                ? dir.register(service, KINDS)
                : dir.register(service, KINDS, modifier);
        keys.put(key, dir);
    }

    /**
     * Registers {@code root} and the directories under it, skipping what {@link #skipped}
     * names in the work tree. Under the git directory - the refs - nothing is skipped:
     * {@code skipped} says no to everything in there, and the refs walk once registered
     * {@code refs} alone, so on Linux a branch created or moved from a terminal, in
     * {@code refs/heads}, went unheard.
     */
    private void registerTree(Path root) throws IOException {
        boolean refs = root.startsWith(gitDir);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // A tab closed part way through a large walk stops it.
                if (closed) return FileVisitResult.TERMINATE;
                if (!refs && !dir.equals(root) && skipped(dir)) return FileVisitResult.SKIP_SUBTREE;
                if (keys.size() >= MAX_DIRECTORIES) {
                    LOGGER.fine("Watching only the first " + MAX_DIRECTORIES + " directories of " + workTree);
                    return FileVisitResult.TERMINATE;
                }
                register(dir, null);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** A work-tree directory not worth descending into: the git directory, or a heavy ignored one. */
    private boolean skipped(Path dir) {
        if (dir.equals(gitDir) || dir.startsWith(gitDir)) return true;
        Path name = dir.getFileName();
        if (name == null) return false;
        String n = name.toString();
        if (n.equals(".git")) return true;
        if (ALWAYS_SKIPPED.contains(n) || ignoredNamesAnywhere.contains(n)) return true;
        return dir.getParent() != null && dir.getParent().equals(workTree) && ignoredNamesAtRoot.contains(n);
    }

    /**
     * Plain directory names from the root {@code .gitignore} - {@code target/},
     * {@code /build} - the cheap and common subset of ignore rules. Anything with a
     * wildcard, a negation or a path in it is left alone: missing an ignore costs a
     * status scan, wrongly ignoring a tracked directory would cost a missed change.
     */
    private void readRootIgnores() {
        Path gitignore = workTree.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) return;
        try {
            for (String raw : Files.readAllLines(gitignore)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                if (line.chars().anyMatch(c -> "*?[\\".indexOf(c) >= 0)) continue;
                boolean anchored = line.startsWith("/");
                String name = line.replaceAll("^/+", "").replaceAll("/+$", "");
                if (name.isEmpty() || name.contains("/")) continue;
                (anchored ? ignoredNamesAtRoot : ignoredNamesAnywhere).add(name);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not read " + gitignore, e);
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    private void run() {
        if (!registerAll()) return;
        while (!closed) {
            WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            Path dir = keys.get(key);
            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    relevant = true;
                    continue;
                }
                if (dir == null || !(event.context() instanceof Path name)) continue;
                Path changed = dir.resolve(name);
                if (isRelevant(changed)) relevant = true;
                // A new directory is registered when it is one this would have registered
                // at the start: in the work tree and not skipped, or a new namespace of refs
                // (refs/heads/feature/, a remote's folder under refs/remotes/).
                if (!wholeTree && event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                        && Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)
                        && (changed.startsWith(gitDir.resolve("refs")) || !skipped(changed))) {
                    try {
                        registerTree(changed);
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.FINE, "Could not watch new directory " + changed, e);
                    }
                }
            }
            if (!key.reset()) keys.remove(key);
            if (relevant) schedule();
        }
    }

    /** Whether a change to {@code path} could be something the tab shows. */
    boolean isRelevant(Path path) {
        Path p = path.toAbsolutePath().normalize();
        String file = p.getFileName() == null ? "" : p.getFileName().toString();
        if (file.endsWith(".lock")) return false;
        if (p.startsWith(gitDir)) {
            if (p.equals(gitDir)) return false;
            Path rel = gitDir.relativize(p);
            return !GIT_NOISE.contains(rel.getName(0).toString());
        }
        if (!p.startsWith(workTree)) return false;
        Path rel = workTree.relativize(p);
        for (int i = 0; i < rel.getNameCount(); i++) {
            String segment = rel.getName(i).toString();
            // A nested repository's own .git (a submodule's, say) churns like ours does.
            if (segment.equals(".git")) return false;
            if (i < rel.getNameCount() - 1 || Files.isDirectory(p)) {
                if (ALWAYS_SKIPPED.contains(segment) || ignoredNamesAnywhere.contains(segment)) return false;
                if (i == 0 && ignoredNamesAtRoot.contains(segment)) return false;
            }
        }
        return true;
    }

    private synchronized void schedule() {
        if (closed) return;
        if (pending != null) pending.cancel(false);
        pending = timer.schedule(this::fire, debounceMillis, TimeUnit.MILLISECONDS);
    }

    private void fire() {
        if (closed) return;
        // The application's own operation, or its aftermath; it refreshes the tab
        // itself, and a second refresh on its heels is exactly the noise to avoid.
        if (ownWorkInProgress.getAsBoolean()) return;
        try {
            onChange.run();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Repository change handler failed", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            service.close();
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Error closing the watch service", e);
        }
        timer.shutdownNow();
    }

    /** For tests: how many directories are registered. */
    int watchedDirectories() {
        return keys.size();
    }

    /** For tests: whether the whole tree is covered by one registration. */
    boolean watchesWholeTree() {
        return wholeTree;
    }
}
