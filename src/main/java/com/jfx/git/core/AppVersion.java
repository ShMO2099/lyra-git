package com.jfx.git.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The application's own version, and how two versions compare.
 *
 * <p>Read from the jar's manifest, which the build stamps from the pom. It used to be a
 * string constant in the About dialog that said "1.0" whatever was actually running.
 * From a build tree there is no manifest and so no version - which is reported as that,
 * rather than as a number that would make a development build look out of date, or up
 * to date, by accident.
 */
public final class AppVersion {

    /** What {@link #current()} answers when the application is not running from a packaged jar. */
    public static final String DEVELOPMENT = "development build";

    private AppVersion() {
    }

    /** This build's version, or {@link #DEVELOPMENT}. */
    public static String current() {
        String implementation = AppVersion.class.getPackage().getImplementationVersion();
        return implementation == null || implementation.isBlank() ? DEVELOPMENT : implementation.trim();
    }

    /** Whether a version is one that can be compared: it starts with a number. */
    public static boolean isComparable(String version) {
        String cleaned = clean(version);
        return !cleaned.isEmpty() && Character.isDigit(cleaned.charAt(0));
    }

    /**
     * Whether {@code candidate} is a later version than {@code installed}.
     *
     * <p>Numbers are compared part by part, so 1.10 is later than 1.9 and 1.2 is the
     * same as 1.2.0. A leading {@code v} is ignored, since release tags usually carry
     * one. With equal numbers, a plain release is later than its own pre-release:
     * 1.2 is later than 1.2-SNAPSHOT and 1.2-rc1, which is the order they are made in.
     * Anything that cannot be read as a version is never "newer" - an update notice
     * about a tag called {@code nightly} would be noise.
     */
    public static boolean isNewer(String candidate, String installed) {
        if (!isComparable(candidate) || !isComparable(installed)) {
            return false;
        }
        List<Integer> a = numbers(candidate);
        List<Integer> b = numbers(installed);
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int left = i < a.size() ? a.get(i) : 0;
            int right = i < b.size() ? b.get(i) : 0;
            if (left != right) {
                return left > right;
            }
        }
        // Same numbers: only "release after its own pre-release" counts as newer.
        return qualifier(candidate).isEmpty() && !qualifier(installed).isEmpty();
    }

    private static String clean(String version) {
        if (version == null) return "";
        String trimmed = version.trim();
        return trimmed.toLowerCase(Locale.ROOT).startsWith("v") ? trimmed.substring(1) : trimmed;
    }

    /** The dotted numbers at the front: {@code 1.2.3} of {@code v1.2.3-rc1}. */
    private static List<Integer> numbers(String version) {
        List<Integer> parts = new ArrayList<>();
        String cleaned = clean(version);
        int end = 0;
        while (end < cleaned.length() && (Character.isDigit(cleaned.charAt(end)) || cleaned.charAt(end) == '.')) {
            end++;
        }
        for (String part : cleaned.substring(0, end).split("\\.")) {
            if (part.isEmpty()) continue;
            try {
                parts.add(Integer.parseInt(part));
            } catch (NumberFormatException tooLong) {
                parts.add(Integer.MAX_VALUE);
            }
        }
        return parts;
    }

    /** What follows the numbers: {@code SNAPSHOT}, {@code rc1}, or nothing. */
    private static String qualifier(String version) {
        String cleaned = clean(version);
        int end = 0;
        while (end < cleaned.length() && (Character.isDigit(cleaned.charAt(end)) || cleaned.charAt(end) == '.')) {
            end++;
        }
        return cleaned.substring(end).replaceAll("^[-+._]+", "");
    }
}
