package com.jfx.git.graph;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Commit ages, written the way you would say them.
 *
 * <p>Replaces a fixed {@code yyyy-MM-dd HH:mm} in the graph's age column. A wall of
 * identical-length timestamps is read by shape rather than value; {@code 5h} and
 * {@code 3d} carry the only part of it anyone scans for. The absolute time is still
 * available, on the tooltip.
 *
 * <p>The short forms are precomputed. There are only ninety of them, they are produced
 * for every visible row on every scroll tick, and building the same eighty-odd strings
 * over and over is exactly the kind of allocation the graph's hot path is not allowed.
 */
public final class RelativeTime {

    private RelativeTime() {}

    /** "just now", then 1–59 minutes, 1–23 hours, 1–6 days: built once, handed out forever. */
    private static final String[] MINUTES = new String[60];
    private static final String[] HOURS   = new String[24];
    private static final String[] DAYS    = new String[7];

    static {
        for (int i = 0; i < MINUTES.length; i++) MINUTES[i] = i + "m";
        for (int i = 0; i < HOURS.length; i++)   HOURS[i]   = i + "h";
        for (int i = 0; i < DAYS.length; i++)    DAYS[i]    = i + "d";
    }

    private static final String NOW = "now";

    /** Within this year: "4 Mar". Older: "Mar 2024" — the day stops mattering by then. */
    private static final DateTimeFormatter THIS_YEAR = DateTimeFormatter.ofPattern("d MMM");
    private static final DateTimeFormatter OLDER     = DateTimeFormatter.ofPattern("MMM yyyy");

    private static final DateTimeFormatter ABSOLUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** How long ago {@code when} was, relative to now. */
    public static String of(Instant when) {
        return of(when, Instant.now());
    }

    /**
     * How long ago {@code when} was, relative to {@code now}.
     *
     * <p>A commit dated in the future is reported as "now" rather than as a negative age:
     * it means a skewed clock somewhere, and the graph is not the place to argue about it.
     */
    static String of(Instant when, Instant now) {
        if (when == null) return "";

        long seconds = Duration.between(when, now).getSeconds();
        if (seconds < 60) return NOW;

        long minutes = seconds / 60;
        if (minutes < 60) return MINUTES[(int) minutes];

        long hours = minutes / 60;
        if (hours < 24) return HOURS[(int) hours];

        long days = hours / 24;
        if (days < 7) return DAYS[(int) days];

        var date = when.atZone(ZoneId.systemDefault());
        var today = now.atZone(ZoneId.systemDefault());
        return date.getYear() == today.getYear()
                ? THIS_YEAR.format(date)
                : OLDER.format(date);
    }

    /** The full timestamp, for the tooltip behind the short form. */
    public static String absolute(Instant when) {
        return when == null ? "" : ABSOLUTE.format(when.atZone(ZoneId.systemDefault()));
    }
}
