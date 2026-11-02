package com.jfx.git.ui;

import com.jfx.git.core.Settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dates read the same everywhere: relative in the phrasing each place was written for,
 * or absolute in the one format the user chose - and a pattern that is not one is
 * refused rather than stored.
 */
class DateDisplayTest {

    private static final Instant NOW = Instant.parse("2026-03-04T12:00:00Z");
    private static final Instant WHEN = Instant.parse("2025-11-21T09:41:27Z");

    @AfterEach
    void defaults() {
        Settings.put(DateDisplay.KEY_MODE, null);
        Settings.put(DateDisplay.KEY_FORMAT, null);
        Settings.put(DateDisplay.KEY_PATTERN, null);
        DateDisplay.reload();
    }

    private static String written(String pattern, Instant when) {
        return DateTimeFormatter.ofPattern(pattern).format(when.atZone(ZoneId.systemDefault()));
    }

    // ------------------------------------------------------------------
    // Relative phrasing: was FileHistoryView's and ReflogView's own
    // ------------------------------------------------------------------

    private static String spoken(Duration age) {
        return DateDisplay.relative(NOW.minus(age), NOW, DateDisplay.Style.SPOKEN);
    }

    @Test
    void spokenAgesReadAsASentence() {
        assertEquals("just now", spoken(Duration.ofSeconds(20)));
        assertEquals("1 minute ago", spoken(Duration.ofMinutes(1)));
        assertEquals("32 minutes ago", spoken(Duration.ofMinutes(32)));
        assertEquals("59 minutes ago", spoken(Duration.ofMinutes(59)));
        assertEquals("1 hour ago", spoken(Duration.ofHours(1)));
        assertEquals("5 hours ago", spoken(Duration.ofHours(5)));
        assertEquals("a day ago", spoken(Duration.ofDays(1)));
        assertEquals("3 days ago", spoken(Duration.ofDays(3)));
        assertEquals("2 months ago", spoken(Duration.ofDays(60)));
        assertEquals("1 year ago", spoken(Duration.ofDays(400)));
        assertEquals("2 years ago", spoken(Duration.ofDays(800)));
    }

    /** The reflog is scanned for "the thing I did just now", so its ages are short. */
    @Test
    void shortAgesAreQuickToScan() {
        DateDisplay.Style style = DateDisplay.Style.SHORT;
        assertEquals("just now", DateDisplay.relative(NOW, NOW, style));
        assertEquals("5m ago", DateDisplay.relative(NOW.minus(Duration.ofMinutes(5)), NOW, style));
        assertEquals("3h ago", DateDisplay.relative(NOW.minus(Duration.ofHours(3)), NOW, style));
        assertEquals("2d ago", DateDisplay.relative(NOW.minus(Duration.ofDays(2)), NOW, style));
    }

    @Test
    void noTimeShowsNothingRatherThanGuessing() {
        for (DateDisplay.Style style : DateDisplay.Style.values()) {
            assertEquals("", DateDisplay.relative(null, style));
            assertEquals("", DateDisplay.of(null, style));
        }
        assertEquals("", DateDisplay.absolute(null));
        assertEquals("", DateDisplay.other(null));
    }

    @Test
    void relativeIsTheDefaultAndKeepsEachPlacesPhrasing() {
        assertEquals(DateDisplay.Mode.RELATIVE, DateDisplay.mode());
        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(5));
        assertEquals("5m", DateDisplay.compact(fiveMinutesAgo), "the graph's narrow column");
        assertEquals("5m ago", DateDisplay.of(fiveMinutesAgo, DateDisplay.Style.SHORT));
        assertEquals("5 minutes ago", DateDisplay.of(fiveMinutesAgo, DateDisplay.Style.SPOKEN));
        assertEquals(DateDisplay.absolute(fiveMinutesAgo), DateDisplay.other(fiveMinutesAgo),
                "the exact time is behind the relative one");
    }

    // ------------------------------------------------------------------
    // Absolute
    // ------------------------------------------------------------------

    @Test
    void absoluteWritesEveryStyleInTheChosenFormat() {
        DateDisplay.setMode(DateDisplay.Mode.ABSOLUTE);

        String expected = written(DateDisplay.ISO_PATTERN, WHEN);
        for (DateDisplay.Style style : DateDisplay.Style.values()) {
            assertEquals(expected, DateDisplay.of(WHEN, style));
        }
        assertEquals(DateDisplay.relative(WHEN, DateDisplay.Style.SPOKEN), DateDisplay.other(WHEN),
                "and how long ago is behind it");
    }

    @Test
    void aCustomPatternIsUsedOnceItIsOne() {
        assertTrue(DateDisplay.setCustomPattern("dd.MM.yyyy HH:mm:ss"));
        DateDisplay.setFormat(DateDisplay.Format.CUSTOM);

        assertEquals(written("dd.MM.yyyy HH:mm:ss", WHEN), DateDisplay.absolute(WHEN));
        assertEquals("dd.MM.yyyy HH:mm:ss", DateDisplay.customPattern());
    }

    @Test
    void anInvalidPatternIsRefusedAndTheLastGoodOneStays() {
        assertTrue(DateDisplay.setCustomPattern("yyyy/MM/dd"));
        DateDisplay.setFormat(DateDisplay.Format.CUSTOM);

        for (String bad : new String[]{"", "   ", "yyyy-MM-dd 'unclosed", "{yyyy}", "bbbb"}) {
            assertNotNull(DateDisplay.problemWith(bad), "\"" + bad + "\" should be refused");
            assertFalse(DateDisplay.setCustomPattern(bad), "\"" + bad + "\" should not be stored");
        }
        assertNull(DateDisplay.problemWith("EEE d MMM yyyy"));
        assertEquals("yyyy/MM/dd", DateDisplay.customPattern());
        assertEquals(written("yyyy/MM/dd", WHEN), DateDisplay.absolute(WHEN));
    }

    /** The settings file is edited by hand; a broken pattern there falls back rather than throwing on every row. */
    @Test
    void aBrokenStoredPatternFallsBackToTheDefault() {
        Settings.put(DateDisplay.KEY_PATTERN, "{{");
        Settings.put(DateDisplay.KEY_FORMAT, DateDisplay.Format.CUSTOM.name());
        Settings.put(DateDisplay.KEY_MODE, DateDisplay.Mode.ABSOLUTE.name());
        DateDisplay.reload();

        assertEquals(written(DateDisplay.ISO_PATTERN, WHEN), DateDisplay.compact(WHEN));
    }

    @Test
    void theLocaleFormatIsNotTheDefaultOne() {
        DateDisplay.setFormat(DateDisplay.Format.LOCALE);
        String text = DateDisplay.absolute(WHEN);
        assertFalse(text.isBlank());
        assertNotEquals(written(DateDisplay.ISO_PATTERN, WHEN), text);
        assertEquals(text, DateDisplay.preview(DateDisplay.Format.LOCALE, "", WHEN));
    }

    // ------------------------------------------------------------------
    // The graph's hot path
    // ------------------------------------------------------------------

    /**
     * Scrolling back over the same commits finds the text already built - the same
     * string, not an equal one made again - and a change of format is never served from
     * what was built for the old one.
     */
    @Test
    void absoluteTextIsCachedAndForgottenOnAChange() {
        DateDisplay.setMode(DateDisplay.Mode.ABSOLUTE);
        String first = DateDisplay.compact(WHEN);
        assertSame(first, DateDisplay.compact(WHEN));
        assertSame(first, DateDisplay.absolute(WHEN));

        long generation = DateDisplay.generation();
        assertTrue(DateDisplay.setCustomPattern("HH:mm dd/MM/yy"));
        DateDisplay.setFormat(DateDisplay.Format.CUSTOM);
        assertTrue(DateDisplay.generation() > generation);
        assertEquals(written("HH:mm dd/MM/yy", WHEN), DateDisplay.compact(WHEN));
    }

    @Test
    void theAgeColumnWidensOnlyForAbsoluteDates() {
        assertEquals(66, DateDisplay.graphAgeWidth(66), "relative ages fit the column they were sized for");
        DateDisplay.setMode(DateDisplay.Mode.ABSOLUTE);
        int width = DateDisplay.graphAgeWidth(66);
        assertTrue(width >= written(DateDisplay.ISO_PATTERN, WHEN).length() * 6, "got " + width);
    }
}
