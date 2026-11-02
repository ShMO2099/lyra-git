package com.jfx.git.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The issue key a branch name carries, and the summary prefix made from it.
 *
 * <p>Teams that track work in Jira, Linear or YouTrack name branches after the ticket -
 * {@code feature/ABC-123-login} - and want every commit on it to start with
 * {@code ABC-123: } so the tracker links them. Typing the key by hand on every commit is
 * exactly the kind of thing that gets forgotten on the one commit that mattered, so the
 * composer starts an empty summary with it.
 *
 * <p>Both the pattern and the prefix's shape are settings: one team writes
 * {@code [ABC-123] }, another {@code ABC-123 }. A pattern with a capturing group takes
 * the group as the key, so a key buried in more context can still be picked out.
 */
public final class BranchIssueKey {

    /** An upper-case project key, a dash and a number: Jira's shape, and most others'. */
    public static final String DEFAULT_PATTERN = "[A-Z][A-Z0-9]+-\\d+";

    /** Where the key goes in the prefix. */
    public static final String KEY_TOKEN = "{key}";

    public static final String DEFAULT_FORMAT = KEY_TOKEN + ": ";

    private BranchIssueKey() {
    }

    /**
     * The first issue key in {@code branch}, or {@code null} when there is none or the
     * pattern does not compile.
     *
     * <p>A pattern with a capturing group answers with the group; without one, with the
     * whole match.
     */
    public static String keyIn(String branch, String pattern) {
        if (branch == null || branch.isBlank()) return null;
        Pattern compiled = compile(pattern);
        if (compiled == null) return null;
        Matcher matcher = compiled.matcher(branch);
        if (!matcher.find()) return null;
        String key = matcher.groupCount() >= 1 && matcher.group(1) != null
                ? matcher.group(1) : matcher.group();
        return key.isBlank() ? null : key;
    }

    /**
     * What an empty summary on {@code branch} starts with, or {@code ""} when the branch
     * names no issue.
     *
     * @param format the prefix's shape, with {@link #KEY_TOKEN} where the key goes; blank
     *               or without the token means {@link #DEFAULT_FORMAT}, since a prefix
     *               that leaves the key out is not one
     */
    public static String prefixFor(String branch, String pattern, String format) {
        String key = keyIn(branch, pattern);
        if (key == null) return "";
        String shape = format == null || !format.contains(KEY_TOKEN) ? DEFAULT_FORMAT : format;
        return shape.replace(KEY_TOKEN, key);
    }

    /** Whether {@code pattern} is a regular expression this class can use. */
    public static boolean isValidPattern(String pattern) {
        return compile(pattern) != null;
    }

    private static Pattern compile(String pattern) {
        if (pattern == null || pattern.isBlank()) return null;
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }
}
