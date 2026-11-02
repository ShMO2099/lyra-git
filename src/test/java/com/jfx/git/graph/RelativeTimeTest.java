package com.jfx.git.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RelativeTimeTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    private static String ago(Duration amount) {
        return RelativeTime.of(NOW.minus(amount), NOW);
    }

    @Test
    @DisplayName("under a minute reads as now")
    void justNow() {
        assertEquals("now", ago(Duration.ZERO));
        assertEquals("now", ago(Duration.ofSeconds(59)));
    }

    @Test
    @DisplayName("minutes, hours and days each get their own unit")
    void unitsStep() {
        assertEquals("1m", ago(Duration.ofSeconds(60)));
        assertEquals("59m", ago(Duration.ofMinutes(59)));
        assertEquals("1h", ago(Duration.ofMinutes(60)));
        assertEquals("23h", ago(Duration.ofHours(23)));
        assertEquals("1d", ago(Duration.ofHours(24)));
        assertEquals("6d", ago(Duration.ofDays(6)));
    }

    @Test
    @DisplayName("past a week it becomes a date, and past a year it drops the day")
    void datesTakeOver() {
        assertEquals("9 Aug", ago(Duration.ofDays(7)));
        // 400 days before 2026-08-16 is 2025-07-12.
        assertEquals("Jul 2025", ago(Duration.ofDays(400)));
    }

    @Test
    @DisplayName("a commit dated in the future reads as now rather than as a negative age")
    void futureIsClamped() {
        // A skewed clock somewhere. The graph is not the place to argue about it.
        assertEquals("now", RelativeTime.of(NOW.plus(Duration.ofHours(3)), NOW));
    }

    @Test
    @DisplayName("the short forms are handed out, not rebuilt")
    void shortFormsAreInterned() {
        // The graph asks for these on every visible row of every scroll tick, so they
        // have to be the same instances rather than fresh strings each time.
        assertSame(ago(Duration.ofMinutes(5)), ago(Duration.ofMinutes(5)));
        assertSame(ago(Duration.ofHours(5)), ago(Duration.ofHours(5)));
        assertSame(ago(Duration.ofDays(3)), ago(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("a missing date produces empty text rather than failing")
    void nullIsEmpty() {
        assertEquals("", RelativeTime.of(null, NOW));
        assertEquals("", RelativeTime.absolute(null));
    }
}
