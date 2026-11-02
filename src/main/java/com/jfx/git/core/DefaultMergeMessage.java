package com.jfx.git.core;

import org.eclipse.jgit.lib.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The subject {@code git merge} writes when it is not given a message - git's
 * {@code fmt-merge-msg}, for the single-head merges this application makes.
 *
 * <h2>Why not JGit's</h2>
 * JGit's {@code MergeMessageFormatter} gets the kinds right but leaves out
 * {@code " into <branch>"} only for {@code master}. git leaves it out for every branch
 * {@code merge.suppressDest} names, and with nothing configured that is {@code main} as
 * well as {@code master} - so on a {@code main} branch, the default for new repositories,
 * the JGit engine's merges read differently from the CLI's. Before this, they did not even
 * say what was merged: the JGit engine wrote {@code "Merge feature"} where git writes
 * {@code "Merge branch 'feature'"}.
 */
final class DefaultMergeMessage {

    /** git's {@code merge.suppressDest} when the setting is absent. */
    private static final List<String> DEFAULT_SUPPRESSED = List.of("main", "master");

    private DefaultMergeMessage() {
    }

    /**
     * @param source       what was merged, as it was named - git quotes it as given
     * @param refName      the full ref {@code source} resolved to, or {@code null} for a
     *                     bare commit
     * @param into         the current branch's short name, {@code HEAD} when detached, or
     *                     {@code null} when it cannot be told
     * @param suppressDest every {@code merge.suppressDest} value in configuration order;
     *                     empty when none is set
     */
    static String of(String source, String refName, String into, List<String> suppressDest) {
        StringBuilder message = new StringBuilder("Merge ").append(kind(refName))
                .append(" '").append(source).append('\'');
        if (into != null && !into.isEmpty() && !suppressed(into, suppressDest)) {
            message.append(" into ").append(into);
        }
        return message.toString();
    }

    private static String kind(String refName) {
        if (refName == null) return "commit";
        if (refName.startsWith(Constants.R_HEADS)) return "branch";
        if (refName.startsWith(Constants.R_REMOTES)) return "remote-tracking branch";
        if (refName.startsWith(Constants.R_TAGS)) return "tag";
        return "commit";
    }

    /**
     * git's reading of the multi-valued setting: each value adds a pattern, an empty one
     * clears what came before, and only when the setting appears nowhere do
     * {@code main} and {@code master} apply. Patterns are wildcards, as git's
     * {@code wildmatch} reads them here - {@code *} crosses {@code /}.
     */
    static boolean suppressed(String branch, List<String> suppressDest) {
        List<String> patterns;
        if (suppressDest == null || suppressDest.isEmpty()) {
            patterns = DEFAULT_SUPPRESSED;
        } else {
            patterns = new ArrayList<>();
            for (String value : suppressDest) {
                if (value == null || value.isEmpty()) {
                    patterns.clear();
                } else {
                    patterns.add(value);
                }
            }
        }
        for (String pattern : patterns) {
            if (globToRegex(pattern).matcher(branch).matches()) return true;
        }
        return false;
    }

    /** {@code *}, {@code ?} and {@code [...]} (with {@code !} or {@code ^} negating); the rest literal. */
    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> {
                    int close = glob.indexOf(']', i + 2);
                    if (close < 0) {
                        regex.append("\\[");
                        break;
                    }
                    String body = glob.substring(i + 1, close);
                    if (body.startsWith("!")) body = "^" + body.substring(1);
                    regex.append('[').append(body.replace("\\", "\\\\")).append(']');
                    i = close;
                }
                case '\\' -> {
                    if (i + 1 < glob.length()) {
                        regex.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
                    } else {
                        regex.append("\\\\");
                    }
                }
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
