package com.jfx.git.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Settings that outlive a session, in one file the user can actually find.
 *
 * <h2>Why not {@code java.util.prefs}</h2>
 *
 * <p>These lived in {@code java.util.prefs}, which sounds like "a settings file" and is
 * not one. On Windows it is the registry, under {@code HKCU\SOFTWARE\JavaSoft\Prefs}, and
 * because registry keys are case-insensitive while {@code Preferences} keys are not, the
 * implementation escapes every capital letter with a slash. A stored repository path came
 * back out looking like this:
 *
 * <pre>/C:///Users///N/O/O/N///Documents///D/E/V///G/I/T//vllm</pre>
 *
 * <p>Which is {@code C:/Users/NOON/Documents/DEV/GIT/vllm}. Settings nobody can read are
 * settings nobody can fix, export, or hand to a colleague.
 *
 * <p>Three further problems came with it. The values were spread over five different
 * registry nodes, because each class asked for a node named after its own package.
 * There are hard limits — keys of 80 characters, values of 8192 — and the recent-repository
 * list packs twenty paths into a single value. And most damagingly, the store is global to
 * the machine and invisible to the project, so a unit test asking for a commit graph read a
 * dropdown the developer had clicked in the GUI an hour earlier, and passed or failed
 * accordingly.
 *
 * <h2>Where it goes now</h2>
 *
 * <ul>
 *   <li>Windows — {@code %APPDATA%\Lyra\settings.properties}</li>
 *   <li>elsewhere — {@code $XDG_CONFIG_HOME/lyra/settings.properties}, or
 *       {@code ~/.config/lyra/settings.properties}</li>
 * </ul>
 *
 * <p>One file, plain text, deletable to reset. The {@code lyra.settings} system property
 * overrides the location, which is how the test suite keeps its hands off the desktop —
 * see the surefire configuration in {@code pom.xml}.
 *
 * <p>Nothing migrates the old registry values across. The application has no users yet, so
 * carrying them over would be maintenance for a case that does not exist; anyone who had
 * settings simply gets the defaults once. The orphaned keys under
 * {@code HKCU\SOFTWARE\JavaSoft\Prefs\com\jfx} can be deleted by hand.
 *
 * <h2>Nothing secret in plain text</h2>
 *
 * <p>As with the store it replaces, this file is readable by anything running as the user.
 * Git credentials stay in memory only; see {@link CredentialsManager}. A remembered
 * hosting-provider token is kept here on Windows, but only as DPAPI ciphertext that only
 * this Windows account can decrypt; see {@link TokenStore}.
 *
 * <h2>Two windows, one file</h2>
 *
 * <p>Each save writes only the keys this process changed, merged into what is on disk at
 * that moment. It used to write its whole in-memory copy, so a second running instance -
 * one launched from the IDE beside one from a shortcut - erased every key the other had
 * added since it started, a remembered token included, the next time it saved its tab
 * order.
 */
public final class Settings {

    private static final Logger LOGGER = Logger.getLogger(Settings.class.getName());

    /** Overrides the location. Set by the test suite; not meant for users. */
    public static final String LOCATION_PROPERTY = "lyra.settings";

    private static final String FILE_NAME = "settings.properties";

    /** Resolved once, then reused. Null until first use or after {@link #useFile}. */
    private static Path file;

    /** The loaded contents. Null until first read. */
    private static Properties values;

    // ------------------------------------------------------------------
    // Write coalescing
    // ------------------------------------------------------------------

    /**
     * How long a burst of writes is allowed to settle before the file is rewritten.
     *
     * <p>Every {@code put} used to rewrite the whole properties file. That is invisible
     * for a preference the user changes once, and wasteful for the ones the UI writes
     * while the user is still moving: graph column order, lane budget, hidden branches
     * and solo state all change several times per drag. Long enough to collapse a drag
     * into one write, short enough that closing the window a moment later still catches
     * it - and {@link #flush()} plus the shutdown hook cover the rest.
     */
    private static final long QUIET_MILLIS = 300;

    private static final ScheduledExecutorService WRITER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "settings-writer");
                t.setDaemon(true);
                return t;
            });

    /** The write that has been scheduled but not yet run, so a later one can replace it. */
    private static ScheduledFuture<?> pending;

    /** True when {@link #values} holds something the file does not. */
    private static boolean dirty;

    /**
     * Keys this process has changed since its last save: the new value, or
     * {@link #REMOVED} for a key it removed. What a save writes; see the class comment.
     */
    private static final java.util.Map<String, String> changed = new java.util.LinkedHashMap<>();

    /** Marks a removed key in {@link #changed}; never a stored value. */
    private static final String REMOVED = new String("\u0000removed");

    static {
        // A daemon writer would otherwise be abandoned mid-window on exit. This is the
        // ordinary way the last setting of a session reaches disk.
        Runtime.getRuntime().addShutdownHook(new Thread(Settings::flush, "settings-flush"));
    }

    private Settings() {
    }

    // ------------------------------------------------------------------
    // Reading and writing
    // ------------------------------------------------------------------

    public static synchronized String get(String key, String fallback) {
        String found = load().getProperty(key);
        return found == null ? fallback : found;
    }

    public static synchronized boolean getBoolean(String key, boolean fallback) {
        String found = load().getProperty(key);
        return found == null ? fallback : Boolean.parseBoolean(found);
    }

    public static synchronized void put(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }
        load().setProperty(key, value);
        changed.put(key, value);
        scheduleSave();
    }

    public static synchronized void putBoolean(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    public static synchronized void remove(String key) {
        // Recorded even when this process never saw the key: another instance may have
        // written it since, and removing it is what was asked. The write is coalesced,
        // so a removal of something absent costs one quiet rewrite at most.
        load().remove(key);
        changed.put(key, REMOVED);
        scheduleSave();
    }

    /**
     * Writes anything still pending, now.
     *
     * <p>Called on shutdown, and by anything that needs the file on disk to be current -
     * a test asserting on its contents, or code about to hand the path to something else.
     * A no-op when nothing has changed.
     */
    public static synchronized void flush() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        if (!dirty) return;
        dirty = false;
        save();
    }

    /** Marks the store dirty and (re)arms the coalescing write. */
    private static void scheduleSave() {
        dirty = true;
        if (pending != null) {
            pending.cancel(false);
        }
        pending = WRITER.schedule(Settings::flush, QUIET_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * An enum value by name, falling back when the stored text names nothing.
     *
     * <p>A value written by a newer version, or edited by hand into nonsense, is not worth
     * failing over — the file is meant to be edited by hand, so it will sometimes be edited
     * wrongly.
     */
    public static <E extends Enum<E>> E getEnum(String key, Class<E> type, E fallback) {
        String stored = get(key, fallback.name());
        try {
            return Enum.valueOf(type, stored);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static void putEnum(String key, Enum<?> value) {
        put(key, value == null ? null : value.name());
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    /** Where the settings are being read from and written to. */
    public static synchronized Path file() {
        if (file == null) {
            String override = System.getProperty(LOCATION_PROPERTY);
            if (override != null && !override.isBlank()) {
                file = Path.of(override);
            } else {
                file = platformFile();
            }
        }
        return file;
    }

    /**
     * Points the store at another file and forgets what was loaded.
     *
     * <p>The seam the tests use. Without it every test inherits the developer's own
     * settings, which is how a graph-ordering preference came to decide whether a lane
     * assertion passed.
     */
    public static synchronized void useFile(Path path) {
        // Anything pending belongs to the old file, and flushing it here would write
        // one test's values into the next one's file. Dropped, not flushed.
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        dirty = false;
        changed.clear();
        file = path;
        values = null;
    }

    /** Where this platform keeps a desktop application's per-user settings. */
    private static Path platformFile() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = appData == null || appData.isBlank()
                    ? Path.of(home, "AppData", "Roaming")
                    : Path.of(appData);
            return adoptLegacyDirectory(base.resolve("JFxGit"), base.resolve("Lyra")).resolve(FILE_NAME);
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg == null || xdg.isBlank() ? Path.of(home, ".config") : Path.of(xdg);
        return adoptLegacyDirectory(base.resolve("jfxgit"), base.resolve("lyra")).resolve(FILE_NAME);
    }

    /**
     * The settings directory, after moving the one the application kept before it was
     * renamed from JFxGit to Lyra.
     *
     * <p>The whole directory moves, not just the settings file: commit-message history,
     * editor drafts, user themes, the graph page cache and, on Windows, the remembered
     * tokens all live beside it. When the move fails - most likely an older build still
     * running with its single-instance lock open inside - the old directory stays in use
     * for this session rather than starting over with defaults, and the next start tries
     * again.
     */
    static Path adoptLegacyDirectory(Path legacy, Path current) {
        if (Files.exists(current) || !Files.isDirectory(legacy)) {
            return current;
        }
        try {
            Files.move(legacy, current);
            LOGGER.info("Moved settings from " + legacy + " to " + current);
            return current;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not move " + legacy + " to " + current
                    + "; using it where it is", e);
            return legacy;
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static Properties load() {
        if (values != null) {
            return values;
        }
        // No file yet simply means defaults, which is where a new installation starts.
        values = read(file());
        return values;
    }

    /** The file's contents now; empty when it is missing or unreadable. */
    private static Properties read(Path path) {
        Properties read = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                read.load(in);
            } catch (IOException e) {
                // Unreadable settings are not worth refusing to start over; the user gets
                // defaults and the next write repairs the file.
                LOGGER.log(Level.WARNING, "Could not read " + path + "; using defaults", e);
            }
        }
        return read;
    }

    private static void save() {
        Path path = file();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // Only this process's changes, over the file as it is now: another instance
            // may have written since this one loaded, and its keys are not ours to erase.
            Properties merged = read(path);
            for (var change : changed.entrySet()) {
                if (change.getValue() == REMOVED) {
                    merged.remove(change.getKey());
                } else {
                    merged.setProperty(change.getKey(), change.getValue());
                }
            }
            changed.clear();
            // What the other instance wrote is visible here from now on, too.
            values = merged;

            // Written beside the target and moved into place, so a write interrupted by
            // a crash or a kill leaves the previous settings intact rather than a
            // half-written file that fails to parse on the next start.
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                merged.store(out, "Lyra settings - safe to edit or delete");
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // A setting that cannot be saved is a setting that does not survive the
            // session. That is worth a log line and nothing more.
            LOGGER.log(Level.WARNING, "Could not write " + path, e);
        }
    }

}
