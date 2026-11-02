package com.jfx.git.ui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-commit colour band shown down the left of blame view.
 *
 * <p>Blame is read to find boundaries - where one commit's work ends and another's
 * begins - so what matters is not that the palette is pretty in the abstract but that
 * <em>neighbouring</em> bands are obviously different from each other. Colours are
 * therefore handed out by the order commits first appear down the file, stepping the
 * hue by the golden angle each time, rather than derived from the commit hash: hashing
 * spreads hues evenly across many commits but promises nothing about any particular
 * pair, so two commits that happen to land a few degrees apart used to draw two
 * near-identical stripes directly against one another.
 */
class CommitColourTest {

    private static final Pattern HSB =
            Pattern.compile("hsb\\((\\d+), (\\d+)%, (\\d+)%\\)");

    private record Hsb(int hue, int saturation, int brightness) {
    }

    private static Hsb parse(int index) {
        String css = DiffViewer.bandColour(index);
        Matcher m = HSB.matcher(css);
        assertTrue(m.matches(), "not a parseable hsb() colour: " + css);
        return new Hsb(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
    }

    /** Shortest way round the colour wheel between two hues, in degrees. */
    private static int hueGap(int a, int b) {
        int raw = Math.abs(a - b) % 360;
        return Math.min(raw, 360 - raw);
    }

    @Test
    void theSamePositionAlwaysGivesTheSameBand() {
        assertEquals(DiffViewer.bandColour(0), DiffViewer.bandColour(0));
        assertEquals(DiffViewer.bandColour(7), DiffViewer.bandColour(7));
    }

    @Test
    void neighbouringCommitsAreNeverCloseInHue() {
        // The property the whole approach exists for, and the one hashing could not give.
        for (int i = 0; i < 200; i++) {
            int gap = hueGap(parse(i).hue(), parse(i + 1).hue());
            assertTrue(gap > 60,
                    "commits " + i + " and " + (i + 1) + " are only " + gap
                            + " degrees apart - adjacent bands must be obviously different");
        }
    }

    @Test
    void evenCommitsTwoApartStayDistinct() {
        // Three consecutive groups are visible at once on any real screen, so the band
        // before last must not collide with the current one either.
        for (int i = 0; i < 200; i++) {
            int gap = hueGap(parse(i).hue(), parse(i + 2).hue());
            assertTrue(gap > 40,
                    "commits " + i + " and " + (i + 2) + " are only " + gap + " degrees apart");
        }
    }

    @Test
    void everyBandIsColourfulEnoughToReadAsAHue() {
        for (int i = 0; i < 300; i++) {
            Hsb c = parse(i);
            assertTrue(c.saturation() >= DiffViewer.MIN_BAND_SATURATION,
                    "a band at " + c.saturation() + "% saturation reads as grey, not as a colour");
            assertTrue(c.brightness() >= DiffViewer.MIN_BAND_BRIGHTNESS,
                    "a band at " + c.brightness() + "% brightness disappears against the gutter");
            assertTrue(c.saturation() <= 100 && c.brightness() <= 100,
                    "hsb() percentages must stay in range: " + c);
            assertTrue(c.hue() >= 0 && c.hue() < 360, "hue out of range: " + c.hue());
        }
    }

    @Test
    void bandsVaryInMoreThanHueAlone() {
        Set<Integer> saturations = new HashSet<>();
        Set<Integer> brightnesses = new HashSet<>();
        Set<Hsb> combinations = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            Hsb c = parse(i);
            saturations.add(c.saturation());
            brightnesses.add(c.brightness());
            combinations.add(c);
        }
        assertTrue(saturations.size() >= 3, "saturation should span several steps, saw " + saturations);
        assertTrue(brightnesses.size() >= 3, "brightness should span several steps, saw " + brightnesses);
        assertTrue(combinations.size() > 250,
                "a run of 300 commits should be nearly all distinct bands, saw " + combinations.size());
    }

    @Test
    void hueKeepsCoveringTheWholeWheelOverManyCommits() {
        // Maximal local separation must not come at the cost of only ever using a few
        // sectors - over a long file the bands should still range across every hue.
        Set<Integer> sectors = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            sectors.add(parse(i).hue() / 30);
        }
        assertEquals(12, sectors.size(), "every 30-degree sector of the wheel should be used");
    }
}
