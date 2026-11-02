package com.jfx.git.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Starred repository paths, persisted between sessions, in the order they were
 * favorited.
 *
 * <p>Order matters here in a way it does not for {@link RecentRepositories}: the
 * first nine favorites are what {@code Ctrl+Alt+1-9} addresses, so favoriting one
 * repository must never reorder another's slot the way opening a repository moves it
 * to the front of the Recent list. New favorites are appended, not prepended.
 */
public final class Favorites {

    private static final String KEY = "repo.favorites";
    private static final String SEPARATOR = "\n";

    private Favorites() {
    }

    /** Favorited paths, in the order they were added, with anything no longer on disk filtered out. */
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

    public static boolean isFavorite(File dir) {
        if (dir == null) {
            return false;
        }
        String target = canonical(dir);
        return load().stream().map(Favorites::canonical).anyMatch(target::equals);
    }

    /** Appends {@code dir} as the newest favorite; does nothing if it already is one. */
    public static void add(File dir) {
        if (dir == null || isFavorite(dir)) {
            return;
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (File existing : load()) {
            paths.add(canonical(existing));
        }
        paths.add(canonical(dir));
        Settings.put(KEY, String.join(SEPARATOR, paths));
    }

    public static void remove(File dir) {
        String target = canonical(dir);
        List<String> remaining = load().stream()
                .map(Favorites::canonical)
                .filter(p -> !p.equals(target))
                .toList();
        Settings.put(KEY, String.join(SEPARATOR, remaining));
    }

    public static void toggle(File dir) {
        if (isFavorite(dir)) {
            remove(dir);
        } else {
            add(dir);
        }
    }

    /**
     * The form favourites are compared in: two spellings of one directory - a different
     * case on Windows, a trailing dot, a symlink - are one favourite. For a caller that
     * checks many paths against one {@link #load()}, rather than calling
     * {@link #isFavorite} per path, which reads the settings and resolves every favourite
     * again each time.
     */
    public static String canonicalPath(File f) {
        return canonical(f);
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }
}
