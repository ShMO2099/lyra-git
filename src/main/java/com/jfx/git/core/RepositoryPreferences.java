package com.jfx.git.core;

import com.jfx.git.model.PullMode;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Settings a single repository can disagree with the rest of the application about.
 *
 * <h2>Why some settings are not global</h2>
 * "Fetch every five minutes" is a sensible default and a bad one: correct for the
 * repository you are working in today, wasteful for the twelve you keep open for
 * reference, and actively wrong for one whose remote is a slow VPN. Same for the engine -
 * a repository with an LFS or filter config that only the real git honours needs the
 * native engine, while the others are happier on the fastest available. Same for pull
 * mode: some teams rebase, some merge, and most people work on both kinds of repository.
 *
 * <p>So these three are per-repository overrides on top of the global value, rather than
 * one global answer everyone argues about. Two more have no global value at all: whether
 * the commit box starts with Skip hooks ticked, and which encoding files are first shown in.
 *
 * <h2>Storage</h2>
 * In {@link Settings}, keyed by the repository's own path:
 * {@code repo[C:\work\thing].autoFetch.minutes}. Not a hash of the path - the settings
 * file is meant to be readable and editable by hand, and a file full of
 * {@code repo[8a3f21c9]} entries nobody can match to a repository would be worse than no
 * file. The path is already stored in the recent-repositories list, so this adds no
 * information that was not there.
 *
 * <p>An override that is absent means "use the global setting", which is the state every
 * repository starts in and returns to when the user clears the field.
 */
public final class RepositoryPreferences {

    private static final Logger LOGGER = Logger.getLogger(RepositoryPreferences.class.getName());

    private static final String AUTO_FETCH = "autoFetch.minutes";
    private static final String ENGINE = "git.engine";
    private static final String PULL_MODE = "pull.mode";
    private static final String SKIP_HOOKS = "commit.skipHooks";
    private static final String DEFAULT_ENCODING = "encoding.default";
    private static final String CONFLICT_WARNING = "conflicts.warn";
    private static final String CONFLICT_BASES = "conflicts.baseBranches";

    private RepositoryPreferences() {
    }

    // ------------------------------------------------------------------
    // Auto-fetch
    // ------------------------------------------------------------------

    /** This repository's own interval, or empty when it follows the global setting. */
    public static Optional<Integer> autoFetchIntervalMinutes(File repository) {
        String stored = get(repository, AUTO_FETCH);
        if (stored == null) return Optional.empty();
        try {
            return Optional.of(Math.max(0, Integer.parseInt(stored.trim())));
        } catch (NumberFormatException e) {
            LOGGER.fine("Ignoring unreadable per-repository fetch interval: " + stored);
            return Optional.empty();
        }
    }

    /** What this repository should actually do, override or not. */
    public static int effectiveAutoFetchIntervalMinutes(File repository) {
        return autoFetchIntervalMinutes(repository)
                .orElseGet(AppPreferences::autoFetchIntervalMinutes);
    }

    /** {@code null} clears the override and returns this repository to the global setting. */
    public static void setAutoFetchIntervalMinutes(File repository, Integer minutes) {
        put(repository, AUTO_FETCH, minutes == null ? null : Integer.toString(Math.max(0, minutes)));
    }

    // ------------------------------------------------------------------
    // Engine
    // ------------------------------------------------------------------

    /**
     * This repository's own engine, or empty when it follows the global setting.
     *
     * <p>{@link GitBackendFactory.BackendEngine#AUTO} is not an override. It names a way
     * of choosing an engine rather than an engine, so as a per-repository value it says
     * exactly what an absent override says - "no particular engine for this one" - while
     * being impossible for {@link GitBackendFactory#engineOf} to match, which would make
     * every open of the repository re-open it for no change. The settings dialog does not
     * offer it; this is what happens when the file is edited by hand, which it is meant
     * to be.
     */
    public static Optional<GitBackendFactory.BackendEngine> engine(File repository) {
        String stored = get(repository, ENGINE);
        if (stored == null) return Optional.empty();
        try {
            GitBackendFactory.BackendEngine engine =
                    GitBackendFactory.BackendEngine.valueOf(stored.trim());
            return engine == GitBackendFactory.BackendEngine.AUTO
                    ? Optional.empty() : Optional.of(engine);
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Ignoring unknown per-repository engine: " + stored);
            return Optional.empty();
        }
    }

    public static GitBackendFactory.BackendEngine effectiveEngine(File repository) {
        return engine(repository).orElseGet(GitBackendFactory::getEnginePreference);
    }

    public static void setEngine(File repository, GitBackendFactory.BackendEngine engine) {
        put(repository, ENGINE, engine == null ? null : engine.name());
    }

    // ------------------------------------------------------------------
    // Pull mode
    // ------------------------------------------------------------------

    /** This repository's own pull mode, or empty when it follows the global setting. */
    public static Optional<PullMode> pullMode(File repository) {
        String stored = get(repository, PULL_MODE);
        if (stored == null) return Optional.empty();
        try {
            return Optional.of(PullMode.valueOf(stored.trim()));
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Ignoring unknown per-repository pull mode: " + stored);
            return Optional.empty();
        }
    }

    public static PullMode effectivePullMode(File repository) {
        return pullMode(repository).orElseGet(AppPreferences::defaultPullMode);
    }

    public static void setPullMode(File repository, PullMode mode) {
        put(repository, PULL_MODE, mode == null ? null : mode.name());
    }

    // ------------------------------------------------------------------
    // Skipping hooks
    // ------------------------------------------------------------------

    /**
     * Whether the commit composer starts with Skip hooks ticked in this repository.
     *
     * <p>For a repository whose hooks are slow or broken and not the user's to fix. Off
     * unless asked for: hooks are the team's checks, and skipping them is a choice the
     * user makes about one repository, never a default for all of them.
     */
    public static boolean skipHooksByDefault(File repository) {
        return Boolean.parseBoolean(get(repository, SKIP_HOOKS));
    }

    /** {@code false} removes the setting, which is the same as never having set it. */
    public static void setSkipHooksByDefault(File repository, boolean skip) {
        put(repository, SKIP_HOOKS, skip ? "true" : null);
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /**
     * What the diff viewer decodes this repository's files as until told otherwise:
     * {@link TextEncoding#GUESS} unless the repository says otherwise - a repository of
     * Shift-JIS or Windows-1252 sources, where every guess is a wrong one.
     */
    public static TextEncoding defaultEncoding(File repository) {
        String stored = get(repository, DEFAULT_ENCODING);
        if (stored == null) return TextEncoding.GUESS;
        try {
            TextEncoding encoding = TextEncoding.valueOf(stored.trim());
            return encoding.isAvailable() ? encoding : TextEncoding.GUESS;
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Ignoring unknown per-repository encoding: " + stored);
            return TextEncoding.GUESS;
        }
    }

    /** {@code null} or {@link TextEncoding#GUESS} returns this repository to guessing. */
    public static void setDefaultEncoding(File repository, TextEncoding encoding) {
        put(repository, DEFAULT_ENCODING,
                encoding == null || encoding == TextEncoding.GUESS ? null : encoding.name());
    }

    // ------------------------------------------------------------------
    // Conflict prevention
    // ------------------------------------------------------------------

    /** Whether this repository warns about conflicts, or empty when it follows the global setting. */
    public static Optional<Boolean> conflictWarning(File repository) {
        String stored = get(repository, CONFLICT_WARNING);
        if (stored == null) return Optional.empty();
        return Optional.of(Boolean.parseBoolean(stored.trim()));
    }

    public static boolean effectiveConflictWarning(File repository) {
        return AppPreferences.warnAboutConflicts()
                && conflictWarning(repository).orElse(true);
    }

    /** {@code null} returns this repository to the global setting. */
    public static void setConflictWarning(File repository, Boolean on) {
        put(repository, CONFLICT_WARNING, on == null ? null : on.toString());
    }

    /**
     * The branches this repository checks for conflicts against, one pattern per line -
     * see {@link ConflictBase} - or empty for {@link ConflictBase#DEFAULT_PATTERNS}.
     */
    public static Optional<String> conflictBasePatterns(File repository) {
        String stored = get(repository, CONFLICT_BASES);
        return stored == null || stored.isBlank() ? Optional.empty() : Optional.of(stored);
    }

    public static String effectiveConflictBasePatterns(File repository) {
        return conflictBasePatterns(repository).orElse(ConflictBase.DEFAULT_PATTERNS);
    }

    /** Blank, null or the default itself clears the override. */
    public static void setConflictBasePatterns(File repository, String patterns) {
        String value = patterns == null ? null : patterns.strip().replace("\r\n", "\n");
        if (value != null && (value.isEmpty() || value.equals(ConflictBase.DEFAULT_PATTERNS))) {
            value = null;
        }
        put(repository, CONFLICT_BASES, value);
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    /**
     * Forgets every override for a repository - what the user wants when a repository
     * has moved, or when they simply want the defaults back.
     */
    public static void clear(File repository) {
        setAutoFetchIntervalMinutes(repository, null);
        setEngine(repository, null);
        setPullMode(repository, null);
        setSkipHooksByDefault(repository, false);
        setDefaultEncoding(repository, null);
        setConflictWarning(repository, null);
        setConflictBasePatterns(repository, null);
    }

    /** Whether this repository disagrees with the global settings about anything. */
    public static boolean hasOverrides(File repository) {
        return autoFetchIntervalMinutes(repository).isPresent()
                || engine(repository).isPresent()
                || pullMode(repository).isPresent()
                || skipHooksByDefault(repository)
                || defaultEncoding(repository) != TextEncoding.GUESS
                || conflictWarning(repository).isPresent()
                || conflictBasePatterns(repository).isPresent();
    }

    private static String get(File repository, String setting) {
        String key = key(repository, setting);
        return key == null ? null : Settings.get(key, null);
    }

    private static void put(File repository, String setting, String value) {
        String key = key(repository, setting);
        if (key == null) return;
        Settings.put(key, value);
    }

    /**
     * {@code repo[<canonical path>].<setting>}.
     *
     * <p>Canonical, so the same repository reached through a symlink, a relative path or
     * a differently-cased Windows drive letter is one repository rather than several with
     * separate settings. Falls back to the absolute path when the filesystem cannot
     * answer - a settings key that is slightly wrong beats losing the setting.
     */
    static String key(File repository, String setting) {
        if (repository == null) return null;
        String path;
        try {
            path = repository.getCanonicalPath();
        } catch (IOException e) {
            path = repository.getAbsolutePath();
        }
        return "repo[" + path + "]." + setting;
    }
}
