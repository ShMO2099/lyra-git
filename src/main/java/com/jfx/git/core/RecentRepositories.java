package com.jfx.git.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Most-recently-opened repository paths, persisted between sessions.
 *
 * <p>The app previously remembered only a single {@code lastRepo} key, which was not
 * enough to drive a launchpad-style list.
 */
public final class RecentRepositories {

    private static final String KEY = "repo.recent";
    private static final String SEPARATOR = "\n";
    private static final int MAX_ENTRIES = 20;

    private RecentRepositories() {
    }

    /** Recorded paths, most recent first, with anything no longer on disk filtered out. */
    public static List<File> load() {
        String raw = Settings.get(KEY, "");
        List<File> out = new ArrayList<>();
        if (raw.isBlank()) {
            return out;
        }
        for (String path : raw.split(SEPARATOR)) {
            if (path.isBlank()) continue;
            File f = new File(path);
            if (f.isDirectory()) {
                out.add(f);
            }
        }
        return out;
    }

    /** Moves {@code dir} to the front of the list. */
    public static void add(File dir) {
        if (dir == null) return;

        // LinkedHashSet keeps insertion order while removing the earlier duplicate.
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add(canonical(dir));
        for (File existing : load()) {
            paths.add(canonical(existing));
        }

        List<String> capped = paths.stream().limit(MAX_ENTRIES).toList();
        Settings.put(KEY, String.join(SEPARATOR, capped));
    }

    public static void remove(File dir) {
        String target = canonical(dir);
        List<String> remaining = load().stream()
                .map(RecentRepositories::canonical)
                .filter(p -> !p.equals(target))
                .toList();
        Settings.put(KEY, String.join(SEPARATOR, remaining));
    }

    public static void clear() {
        Settings.remove(KEY);
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }
}
