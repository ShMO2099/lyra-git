package com.jfx.git.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which repositories were open in the tab strip, and what the user calls them.
 *
 * <h2>Why this is not two booleans in {@link AppPreferences}</h2>
 * Both facts are keyed by repository path and both have to survive a restart, but neither
 * is a setting the user sets directly — they are a record of what the window looked like.
 * Keeping them together means the reopen path reads one class, and means the alias of a
 * repository that is no longer open is still there when it is opened again.
 *
 * <h2>Storage shape</h2>
 * {@link Settings} is a {@code java.util.Properties} file, so a value may contain
 * anything as long as it round-trips — {@code Properties} escapes newlines and tabs on
 * write and restores them on read. Records are therefore newline-separated and their two
 * fields tab-separated, rather than the path being built into the key: a key made of a
 * Windows path is legal but unreadable in the file, and impossible to scan by eye when
 * something goes wrong.
 *
 * <p>A record that does not parse is dropped rather than repaired. The cost of losing one
 * remembered tab is a tab the user reopens; the cost of guessing at a half-written path is
 * opening the wrong repository.
 */
public final class TabSession {

    private static final String KEY_OPEN = "tabs.open";
    private static final String KEY_ACTIVE = "tabs.active";
    private static final String KEY_ALIASES = "tabs.aliases";
    private static final String KEY_REMEMBER = "tabs.remember";

    private static final String RECORD = "\n";
    private static final String FIELD = "\t";

    private TabSession() {
    }

    // ------------------------------------------------------------------
    // Remember Tabs
    // ------------------------------------------------------------------

    /**
     * Whether to reopen last session's tabs at startup.
     *
     * <p>Defaults to on, which is what commercial clients do and what someone who left four
     * repositories open expects to find.
     */
    public static boolean remember() {
        return Settings.getBoolean(KEY_REMEMBER, true);
    }

    public static void setRemember(boolean remember) {
        Settings.putBoolean(KEY_REMEMBER, remember);
    }

    // ------------------------------------------------------------------
    // The open set
    // ------------------------------------------------------------------

    /**
     * The repository paths that were open, in strip order.
     *
     * <p>Empty when nothing was recorded or the feature is off. Paths are returned as
     * stored; the caller checks they still exist, because a repository can be deleted
     * between sessions and a missing one must not stop the rest from opening.
     */
    public static List<String> openPaths() {
        if (!remember()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String record : split(Settings.get(KEY_OPEN, ""))) {
            paths.add(record);
        }
        return List.copyOf(paths);
    }

    /** Index into {@link #openPaths()} that was in front, clamped to that list. */
    public static int activeIndex() {
        try {
            int stored = Integer.parseInt(Settings.get(KEY_ACTIVE, "0"));
            return Math.max(0, stored);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Records the open set.
     *
     * <p>Writing an empty list clears the record, so closing every tab and quitting
     * reopens to the launchpad rather than to whatever was open the session before.
     */
    public static void setOpenPaths(List<String> paths, int activeIndex) {
        if (paths == null || paths.isEmpty()) {
            Settings.remove(KEY_OPEN);
            Settings.remove(KEY_ACTIVE);
            return;
        }
        List<String> canonical = new ArrayList<>(paths.size());
        for (String path : paths) {
            if (path != null && !path.isBlank() && !path.contains(RECORD) && !path.contains(FIELD)) {
                canonical.add(path);
            }
        }
        if (canonical.isEmpty()) {
            Settings.remove(KEY_OPEN);
            Settings.remove(KEY_ACTIVE);
            return;
        }
        Settings.put(KEY_OPEN, String.join(RECORD, canonical));
        Settings.put(KEY_ACTIVE, Integer.toString(
                Math.max(0, Math.min(activeIndex, canonical.size() - 1))));
    }

    // ------------------------------------------------------------------
    // Aliases
    // ------------------------------------------------------------------

    /** The user's name for the repository at {@code path}, or null when unnamed. */
    public static String alias(String path) {
        return path == null ? null : aliases().get(path);
    }

    /**
     * Names the repository at {@code path}, or forgets the name when {@code alias} is
     * blank.
     *
     * <p>Forgetting removes the record rather than storing an empty one, so the file does
     * not accumulate a line per repository the user once renamed and then un-renamed.
     */
    public static void setAlias(String path, String alias) {
        if (path == null || path.isBlank() || path.contains(RECORD) || path.contains(FIELD)) {
            return;
        }
        Map<String, String> all = new LinkedHashMap<>(aliases());
        if (alias == null || alias.isBlank()) {
            all.remove(path);
        } else {
            String cleaned = alias.trim();
            if (cleaned.contains(RECORD) || cleaned.contains(FIELD)) {
                return;
            }
            all.put(path, cleaned);
        }

        if (all.isEmpty()) {
            Settings.remove(KEY_ALIASES);
            return;
        }
        List<String> records = new ArrayList<>(all.size());
        for (Map.Entry<String, String> entry : all.entrySet()) {
            records.add(entry.getKey() + FIELD + entry.getValue());
        }
        Settings.put(KEY_ALIASES, String.join(RECORD, records));
    }

    /** Every stored alias, keyed by path, in the order they were written. */
    public static Map<String, String> aliases() {
        Map<String, String> all = new LinkedHashMap<>();
        for (String record : split(Settings.get(KEY_ALIASES, ""))) {
            int tab = record.indexOf(FIELD);
            // No separator means no name; a leading one means no path. Neither names a
            // repository, so neither is worth keeping.
            if (tab <= 0 || tab == record.length() - 1) {
                continue;
            }
            all.put(record.substring(0, tab), record.substring(tab + 1));
        }
        return all;
    }

    // ------------------------------------------------------------------

    /** The canonical form of {@code dir}, which is the key both maps are stored under. */
    public static String key(File dir) {
        if (dir == null) {
            return null;
        }
        try {
            return dir.getCanonicalPath();
        } catch (Exception e) {
            return dir.getAbsolutePath();
        }
    }

    private static List<String> split(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> records = new ArrayList<>();
        for (String record : stored.split(RECORD)) {
            if (!record.isBlank()) {
                records.add(record);
            }
        }
        return records;
    }
}
