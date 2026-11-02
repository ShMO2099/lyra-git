package com.jfx.git.ui;

import com.jfx.git.core.Settings;
import com.jfx.git.graph.RelativeTime;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * How the application writes a date: relative ("3h", "2 days ago") or absolute, and in
 * which absolute format.
 *
 * <h2>One place</h2>
 * The graph, the file history, the reflog, the history search, the commit details, the
 * blame gutter and the status bar each had their own formatter - {@code yyyy-MM-dd HH:mm}
 * here, {@code dd/MM/yyyy} there, three different ways of saying "an hour ago" - and the
 * blame column even changed format with the engine. They all ask this class now, so a
 * format chosen in Settings is the format everywhere.
 *
 * <h2>Styles of relative</h2>
 * A relative age is phrased for where it stands, which a single setting does not change:
 * {@link Style#COMPACT} for a narrow column ("5m", "3d", "4 Mar"), {@link Style#SHORT}
 * for a list read for "the thing I did just now" ("5m ago"), {@link Style#SPOKEN} where
 * there is room for a sentence ("5 minutes ago"). In absolute mode all three are the
 * chosen format.
 *
 * <h2>The graph's hot path</h2>
 * The graph asks for every visible row on every scroll tick. The relative forms are
 * precomputed in {@link RelativeTime}; absolute text is kept in a small direct-mapped
 * cache keyed by the instant's second, so scrolling back over the same commits finds
 * strings already built instead of formatting them again.
 */
public final class DateDisplay {

    /** Relative ages or absolute timestamps. */
    public enum Mode {
        RELATIVE("Relative"),
        ABSOLUTE("Absolute");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Which absolute format. */
    public enum Format {
        ISO("yyyy-MM-dd HH:mm"),
        LOCALE("Your system's format"),
        CUSTOM("Custom pattern");

        private final String label;

        Format(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** How a relative age is phrased; see the class javadoc. */
    public enum Style {
        COMPACT, SHORT, SPOKEN
    }

    static final String KEY_MODE = "dates.mode";
    static final String KEY_FORMAT = "dates.format";
    static final String KEY_PATTERN = "dates.pattern";

    /** The pattern {@link Format#ISO} stands for. */
    public static final String ISO_PATTERN = "yyyy-MM-dd HH:mm";

    /** Everything the formatting depends on, swapped whole so a reader never sees half of a change. */
    private record Config(Mode mode, Format format, String pattern, DateTimeFormatter formatter, long generation) {
    }

    private static volatile Config config;

    /** One cached text, for one second at one generation. Immutable, so a racing reader sees all of it or none. */
    private record Entry(long second, long generation, String text) {
    }

    private static final int CACHE_SIZE = 4096;
    private static final Entry[] CACHE = new Entry[CACHE_SIZE];

    private DateDisplay() {
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    public static Mode mode() {
        return config().mode();
    }

    public static Format format() {
        return config().format();
    }

    /** The custom pattern, kept even while another format is chosen; blank when there is none. */
    public static String customPattern() {
        return config().pattern();
    }

    public static void setMode(Mode mode) {
        Settings.put(KEY_MODE, (mode == null ? Mode.RELATIVE : mode).name());
        reload();
    }

    public static void setFormat(Format format) {
        Settings.put(KEY_FORMAT, (format == null ? Format.ISO : format).name());
        reload();
    }

    /**
     * Stores {@code pattern} as the custom format, if it is one.
     *
     * @return {@code false}, storing nothing, for a pattern {@link #problemWith} refuses
     */
    public static boolean setCustomPattern(String pattern) {
        if (problemWith(pattern) != null) return false;
        Settings.put(KEY_PATTERN, pattern.trim());
        reload();
        return true;
    }

    /**
     * What is wrong with {@code pattern} as a date format, or {@code null} when nothing is.
     *
     * <p>Tried on a real date as well as parsed: a pattern can parse and still fail to
     * format - one asking for a field a date and time do not have.
     */
    public static String problemWith(String pattern) {
        if (pattern == null || pattern.isBlank()) return "Type a pattern, such as dd.MM.yyyy HH:mm.";
        try {
            DateTimeFormatter.ofPattern(pattern.trim()).format(ZonedDateTime.now());
            return null;
        } catch (IllegalArgumentException | DateTimeException e) {
            return "Not a date pattern: " + e.getMessage();
        }
    }

    /** {@code when} as {@code format} would write it, for the preview beside the choice. */
    public static String preview(Format format, String pattern, Instant when) {
        DateTimeFormatter formatter = formatterFor(format, pattern);
        return formatter.format(when.atZone(ZoneId.systemDefault()));
    }

    /**
     * Bumped on every change of setting; a view that laid itself out for one format checks
     * it to know when to lay itself out again.
     */
    public static long generation() {
        return config().generation();
    }

    /** Reads the settings again; for a settings file swapped underneath, as tests do. */
    public static void reload() {
        Config old = config;
        Mode mode = Settings.getEnum(KEY_MODE, Mode.class, Mode.RELATIVE);
        Format format = Settings.getEnum(KEY_FORMAT, Format.class, Format.ISO);
        String pattern = Settings.get(KEY_PATTERN, "");
        // A custom pattern edited by hand into something that cannot format falls back
        // to the default in formatterFor - not a crash on every row of the graph.
        config = new Config(mode, format, pattern == null ? "" : pattern,
                formatterFor(format, pattern), old == null ? 1 : old.generation() + 1);
    }

    private static Config config() {
        Config current = config;
        if (current == null) {
            synchronized (DateDisplay.class) {
                if (config == null) reload();
                current = config;
            }
        }
        return current;
    }

    private static DateTimeFormatter formatterFor(Format format, String pattern) {
        return switch (format == null ? Format.ISO : format) {
            case ISO -> DateTimeFormatter.ofPattern(ISO_PATTERN);
            // The date at medium length, the time without seconds: "4 Mar 2026, 14:30".
            case LOCALE -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);
            case CUSTOM -> problemWith(pattern) == null
                    ? DateTimeFormatter.ofPattern(pattern.trim())
                    : DateTimeFormatter.ofPattern(ISO_PATTERN);
        };
    }

    // ------------------------------------------------------------------
    // Writing dates
    // ------------------------------------------------------------------

    /** {@code when} as the settings say: relative in {@code style}, or absolute. {@code ""} for null. */
    public static String of(Instant when, Style style) {
        if (when == null) return "";
        return mode() == Mode.ABSOLUTE ? absolute(when) : relative(when, style);
    }

    /** The graph's age column: {@link Style#COMPACT}, or the cached absolute text. */
    public static String compact(Instant when) {
        return of(when, Style.COMPACT);
    }

    /**
     * {@code when} in the chosen absolute format, whatever the mode - for a tooltip
     * behind a relative age, or a detail line beside one.
     */
    public static String absolute(Instant when) {
        if (when == null) return "";
        Config current = config();
        long second = when.getEpochSecond();
        int slot = (int) (mix(second) & (CACHE_SIZE - 1));
        Entry cached = CACHE[slot];
        if (cached != null && cached.second() == second && cached.generation() == current.generation()) {
            return cached.text();
        }
        String text = current.formatter().format(when.atZone(ZoneId.systemDefault()));
        CACHE[slot] = new Entry(second, current.generation(), text);
        return text;
    }

    /**
     * The other half of {@link #of}, for the tooltip behind it: the absolute time behind a
     * relative age, and how long ago behind an absolute one.
     */
    public static String other(Instant when) {
        if (when == null) return "";
        return mode() == Mode.ABSOLUTE ? relative(when, Style.SPOKEN) : absolute(when);
    }

    /** Spreads neighbouring seconds - a run of commits made a minute apart - across the cache. */
    private static long mix(long value) {
        long h = value * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 29);
    }

    /** {@code when} as a relative age in {@code style}, whatever the mode. */
    public static String relative(Instant when, Style style) {
        return relative(when, Instant.now(), style);
    }

    static String relative(Instant when, Instant now, Style style) {
        if (when == null) return "";
        return switch (style == null ? Style.COMPACT : style) {
            case COMPACT -> RelativeTime.of(when);
            case SHORT -> shortAgo(when, now);
            case SPOKEN -> spoken(when, now);
        };
    }

    /** "5m ago", "3h ago", "2d ago". */
    private static String shortAgo(Instant when, Instant now) {
        Duration age = Duration.between(when, now);
        long minutes = age.toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = age.toHours();
        if (hours < 24) return hours + "h ago";
        return age.toDays() + "d ago";
    }

    /** "32 minutes ago", "a day ago", "3 months ago". */
    private static String spoken(Instant when, Instant now) {
        Duration age = Duration.between(when, now);
        long minutes = age.toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");

        long hours = age.toHours();
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");

        long days = age.toDays();
        if (days == 1) return "a day ago";
        if (days < 30) return days + " days ago";

        long months = days / 30;
        if (months < 12) return months + (months == 1 ? " month ago" : " months ago");

        long years = days / 365;
        return years + (years == 1 ? " year ago" : " years ago");
    }

    // ------------------------------------------------------------------
    // Room for it
    // ------------------------------------------------------------------

    /**
     * How wide the graph's age column has to be: {@code relativeWidth} for the short
     * relative forms it was sized for, wider for an absolute format that needs it.
     *
     * <p>Measured in characters of the column's monospaced font against the widest month
     * the format can write, so "September" in a long locale format is not cut to "Sept…".
     */
    public static int graphAgeWidth(int relativeWidth) {
        Config current = config();
        if (current.mode() != Mode.ABSOLUTE) return relativeWidth;
        int widest = 0;
        for (int month = 1; month <= 12; month++) {
            ZonedDateTime sample = ZonedDateTime.of(2026, month, 28, 23, 59, 59, 0, ZoneId.systemDefault());
            widest = Math.max(widest, current.formatter().format(sample).length());
        }
        // 11px Consolas is a little over six pixels a character; the padding is the
        // column's own right inset.
        return Math.max(relativeWidth, (int) Math.ceil(widest * 6.8) + 10);
    }
}
