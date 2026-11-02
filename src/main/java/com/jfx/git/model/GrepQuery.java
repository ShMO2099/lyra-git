package com.jfx.git.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a search in files looks for, and where.
 *
 * <p>The text is read the way the find bar reads it - literally unless {@code regex},
 * case-insensitively unless {@code matchCase}, anywhere in a line unless {@code
 * wholeWord} - so a query means the same thing in a file and across the repository.
 *
 * <h2>The path filter</h2>
 * {@code pathGlob} is a comma-separated list of git pathspecs, read the way {@code git
 * grep -- <pathspec>} reads them, so the two engines agree on what one means without a
 * second dialect to learn:
 * <ul>
 *   <li>a pattern without a wildcard names a file or a folder: {@code src} is everything
 *       under {@code src/};</li>
 *   <li>one with a wildcard is matched against the whole path, and {@code *} crosses
 *       folders: {@code *.java} finds {@code src/a/B.java};</li>
 *   <li>a leading {@code !} leaves matching paths out: {@code !*.min.js}.</li>
 * </ul>
 * Blank means every file.
 *
 * @param regex     the text is a regular expression ({@code git grep -E})
 * @param pathGlob  the path filter above, or blank/null for every file
 */
public record GrepQuery(String text, boolean matchCase, boolean wholeWord, boolean regex,
                        String pathGlob) {

    public GrepQuery {
        text = text == null ? "" : text;
        pathGlob = pathGlob == null ? "" : pathGlob.strip();
    }

    /** A literal, case-insensitive search for {@code text} in every file. */
    public static GrepQuery literal(String text) {
        return new GrepQuery(text, false, false, false, "");
    }

    /** Whether there is nothing to look for. */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** The same query, restricted to {@code glob}. */
    public GrepQuery withPathGlob(String glob) {
        return new GrepQuery(text, matchCase, wholeWord, regex, glob);
    }

    /** The patterns of {@link #pathGlob}, trimmed, blanks dropped, excludes keeping their {@code !}. */
    public List<String> pathPatterns() {
        List<String> patterns = new ArrayList<>();
        for (String part : pathGlob.split(",")) {
            String pattern = part.strip();
            if (!pattern.isEmpty() && !pattern.equals("!")) patterns.add(pattern);
        }
        return patterns;
    }

    /**
     * Whether {@code path} passes the path filter: it matches one of the patterns that
     * are not excludes, or there are none of those, and it matches no exclude.
     */
    public boolean matchesPath(String path) {
        boolean anyInclude = false;
        boolean included = false;
        for (String pattern : pathPatterns()) {
            if (pattern.startsWith("!")) {
                if (pathspecMatches(pattern.substring(1), path)) return false;
            } else {
                anyInclude = true;
                included |= pathspecMatches(pattern, path);
            }
        }
        return !anyInclude || included;
    }

    /**
     * One git pathspec against one path, as git matches it without pathspec magic: a
     * pattern with no wildcard is the path itself or a folder above it; one with a
     * wildcard is matched against the whole path, case-sensitively, with {@code *}
     * crossing folders.
     */
    static boolean pathspecMatches(String pattern, String path) {
        String p = pattern.startsWith("./") ? pattern.substring(2) : pattern;
        if (p.isEmpty() || p.equals(".")) return true;
        if (!hasWildcard(p)) {
            String folder = p.endsWith("/") ? p : p + "/";
            return path.equals(p) || path.startsWith(folder);
        }
        return Pattern.compile(globToRegex(p), Pattern.DOTALL).matcher(path).matches();
    }

    private static boolean hasWildcard(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '\\') return true;
        }
        return false;
    }

    /** fnmatch without FNM_PATHNAME, as a Java regular expression. */
    private static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> out.append(".*");
                case '?' -> out.append('.');
                case '\\' -> {
                    if (i + 1 < glob.length()) {
                        out.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
                    } else {
                        out.append("\\\\");
                    }
                }
                case '[' -> {
                    int close = glob.indexOf(']', i + 2);
                    if (close < 0) {
                        out.append("\\[");
                        break;
                    }
                    String body = glob.substring(i + 1, close);
                    StringBuilder set = new StringBuilder("[");
                    int start = 0;
                    if (body.startsWith("!") || body.startsWith("^")) {
                        set.append('^');
                        start = 1;
                    }
                    for (int j = start; j < body.length(); j++) {
                        char b = body.charAt(j);
                        if (b == '-' && j > start && j < body.length() - 1) {
                            set.append('-');
                        } else if (Character.isLetterOrDigit(b)) {
                            set.append(b);
                        } else {
                            set.append('\\').append(b);
                        }
                    }
                    out.append(set).append(']');
                    i = close;
                }
                default -> out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return out.toString();
    }
}
