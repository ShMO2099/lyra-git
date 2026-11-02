package com.jfx.git.core;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The configuration git itself would read for a repository, as opposed to the one JGit
 * reads.
 *
 * <h2>Why JGit's view is not enough</h2>
 * JGit follows {@code [include]} but silently drops {@code [includeIf]}. That is the
 * mechanism people use to be one person at work and another at home:
 * {@code [includeIf "gitdir:~/work/"] path = .gitconfig-work}, with the work file setting
 * the work email and {@code commit.gpgsign = true}. A JGit commit in a work repository
 * then records the personal email and is silently unsigned - the two things that
 * configuration exists to prevent.
 *
 * <p>So when git is installed, the answer comes from {@code git config --list}, which
 * evaluates every include the way the user's own {@code git commit} would. When it is
 * not, {@link #conditionalIncludesThatCouldApply} says whether JGit's reading might be
 * wrong, so the caller can refuse rather than guess.
 */
final class EffectiveConfig {

    private static final Logger LOGGER = Logger.getLogger(EffectiveConfig.class.getName());

    /**
     * Whether git can be asked. A seam for tests, which need to exercise the JGit-only
     * path on a machine that has git.
     */
    static volatile BooleanSupplier gitAvailable = NativeGitRunner::isGitInstalled;

    /**
     * Extra environment for the {@code git config} subprocess. A seam for tests, which
     * point {@code GIT_CONFIG_GLOBAL} at a scratch file rather than read - or depend on -
     * the developer's real global config.
     */
    static volatile Map<String, String> gitEnvironment = Map.of();

    /** Every value for each key, in the order git read them; the last one wins. */
    private final Map<String, List<String>> values;

    private EffectiveConfig(Map<String, List<String>> values) {
        this.values = values;
    }

    /**
     * What git would read for {@code repository}, or {@code null} when git is not
     * installed or could not answer - the caller then has only JGit's view.
     */
    static EffectiveConfig fromGit(Repository repository) {
        if (!gitAvailable.getAsBoolean()) return null;
        File cwd = repository.isBare() ? repository.getDirectory() : repository.getWorkTree();
        NativeGitRunner.Result result = new NativeGitRunner(cwd)
                .runWithEnvironment(gitEnvironment, List.of("config", "--list", "-z"));
        if (!result.isOk()) {
            LOGGER.log(Level.FINE, "git config --list failed: {0}", result.stderr());
            return null;
        }
        return new EffectiveConfig(parse(result.stdoutBytes()));
    }

    /**
     * Parses {@code git config --list -z}: entries end in NUL, and the key is separated
     * from the value by the first newline. A key with no newline had no {@code =} at all,
     * which git reads as boolean true.
     */
    static Map<String, List<String>> parse(byte[] output) {
        Map<String, List<String>> parsed = new HashMap<>();
        String text = new String(output, StandardCharsets.UTF_8);
        for (String entry : text.split("\0")) {
            if (entry.isEmpty()) continue;
            int newline = entry.indexOf('\n');
            String key = newline < 0 ? entry : entry.substring(0, newline);
            String value = newline < 0 ? null : entry.substring(newline + 1);
            parsed.computeIfAbsent(normaliseKey(key), k -> new ArrayList<>()).add(value);
        }
        return parsed;
    }

    /**
     * Section and variable names are case-insensitive; a subsection is not. Git already
     * prints them this way, and lookups go through here too so a caller can write
     * {@code "i18n.commitEncoding"} the way the documentation spells it.
     */
    private static String normaliseKey(String key) {
        int first = key.indexOf('.');
        int last = key.lastIndexOf('.');
        if (first < 0) return key.toLowerCase(Locale.ROOT);
        String section = key.substring(0, first).toLowerCase(Locale.ROOT);
        String name = key.substring(last + 1).toLowerCase(Locale.ROOT);
        if (first == last) return section + "." + name;
        return section + key.substring(first, last + 1) + name;
    }

    /** The winning value, or {@code null} when unset. A value-less key reads as "true". */
    String get(String key) {
        List<String> all = values.get(normaliseKey(key));
        if (all == null || all.isEmpty()) return null;
        String last = all.get(all.size() - 1);
        return last == null ? "true" : last;
    }

    /** Git's boolean spelling: yes/on/true/1, no/off/false/0/empty, or any integer. */
    boolean getBoolean(String key, boolean fallback) {
        return parseBoolean(get(key), fallback);
    }

    static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) return fallback;
        String v = value.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "true", "yes", "on" -> { return true; }
            case "false", "no", "off", "" -> { return false; }
            default -> {
                try {
                    return Integer.parseInt(v) != 0;
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Without git: could JGit's reading be wrong?
    // ------------------------------------------------------------------

    /**
     * The {@code includeIf} conditions anywhere in JGit's config chain - repository,
     * user and system - that might apply to {@code repository}.
     *
     * <p>"Might", because only {@code gitdir:} conditions can be evaluated here with any
     * confidence, and even that is a reimplementation of git's matching. Every other kind
     * ({@code onbranch:}, {@code hasconfig:remote.*.url:}) and any {@code gitdir:}
     * pattern this cannot evaluate is reported as applicable: a false alarm costs the user
     * a refusal that names the fix, a missed one costs them a commit under the wrong
     * name.
     */
    static List<String> conditionalIncludesThatCouldApply(Repository repository) {
        Config config = repository.getConfig();
        List<String> applicable = new ArrayList<>();
        // getSubsections walks the whole base-config chain, so the user's global
        // ~/.gitconfig and the system config are covered as well as the repository's.
        for (String condition : config.getSubsections("includeIf")) {
            if (couldApply(condition, repository.getDirectory())) {
                applicable.add(condition);
            }
        }
        return applicable;
    }

    static boolean couldApply(String condition, File gitDir) {
        boolean ignoreCase;
        String pattern;
        if (condition.startsWith("gitdir:")) {
            ignoreCase = false;
            pattern = condition.substring("gitdir:".length());
        } else if (condition.startsWith("gitdir/i:")) {
            ignoreCase = true;
            pattern = condition.substring("gitdir/i:".length());
        } else {
            return true;
        }
        try {
            // Relative to the including file's own directory: which file that was is not
            // something JGit's merged view remembers, so it cannot be evaluated.
            if (pattern.startsWith("./")) return true;
            if (pattern.startsWith("~/")) {
                pattern = System.getProperty("user.home").replace('\\', '/') + pattern.substring(1);
            }
            pattern = pattern.replace('\\', '/');
            boolean absolute = pattern.startsWith("/") || pattern.matches("^[A-Za-z]:/.*");
            if (!absolute) pattern = "**/" + pattern;
            if (pattern.endsWith("/")) pattern = pattern + "**";

            String path = gitDir.getAbsolutePath().replace('\\', '/');
            String canonical;
            try {
                canonical = gitDir.getCanonicalPath().replace('\\', '/');
            } catch (java.io.IOException e) {
                canonical = path;
            }
            Pattern regex = Pattern.compile(globToRegex(pattern),
                    ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            return regex.matcher(path).matches() || regex.matcher(canonical).matches();
        } catch (RuntimeException e) {
            // A pattern this cannot read is one this cannot rule out.
            return true;
        }
    }

    /** Git's wildmatch with WM_PATHNAME: {@code *} stays within a path segment, {@code **} does not. */
    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    i++;
                    // "**/" matches zero or more whole directories.
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '[') {
                int close = glob.indexOf(']', i + 1);
                if (close < 0) {
                    regex.append("\\[");
                } else {
                    String set = glob.substring(i + 1, close);
                    if (set.startsWith("!")) set = "^" + set.substring(1);
                    regex.append('[').append(set.replace("\\", "\\\\")).append(']');
                    i = close;
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }
}
