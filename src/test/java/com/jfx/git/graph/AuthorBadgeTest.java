package com.jfx.git.graph;

import com.jfx.git.ui.FxHarness;

import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Author badges: the initials, the colour, and the fact that neither is a bitmap.
 *
 * <p>The badge replaced a rasterised avatar because a bitmap is a fixed grid of pixels and
 * the zoom multiplies whatever it was rendered at. These check the drawn version keeps the
 * identity rules the raster had — same initials, same colour per person — and that it is
 * built from nodes, which is what makes it sharp at any size.
 */
class AuthorBadgeTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    private static AuthorBadge badge(String name, String email) {
        return FxHarness.call(() -> {
            AuthorBadge b = new AuthorBadge(24);
            b.setAuthor(name, email);
            b.applyCss();
            // No parent to size it, so it sizes itself the way a layout pass would.
            b.autosize();
            b.layout();
            return b;
        });
    }

    private static String textOf(AuthorBadge badge) {
        return FxHarness.call(() -> badge.getChildrenUnmodifiable().stream()
                .filter(n -> n instanceof Label)
                .map(n -> ((Label) n).getText())
                .findFirst().orElse(null));
    }

    private static Color fillOf(AuthorBadge badge) {
        Background background = FxHarness.call(badge::getBackground);
        assertNotNull(background, "a badge should be filled with the author's colour");
        return (Color) background.getFills().get(0).getFill();
    }

    // ------------------------------------------------------------------
    // Initials
    // ------------------------------------------------------------------

    @Test
    void twoNamesGiveTwoInitials() {
        assertEquals("AL", textOf(badge("Ada Lovelace", "ada@example.com")));
    }

    @Test
    void oneNameGivesItsFirstTwoLetters() {
        // A bare "S" would not separate Shvan from Steve.
        assertEquals("SH", textOf(badge("Shvan", "s@example.com")));
    }

    @Test
    void theEmailStandsInWhenThereIsNoName() {
        assertEquals("AD", textOf(badge("", "ada@example.com")));
    }

    @Test
    void theDomainIsNotMistakenForASurname() {
        // Splitting the whole address took the A of "ada" and the C of "com", so every
        // author at a .com address whose name began with A looked identical.
        assertEquals("AB", textOf(badge("", "ada.byron@example.com")));
        assertNotEquals(textOf(badge("", "ada@example.com")),
                textOf(badge("", "alan@example.com")));
    }

    @Test
    void anAuthorWithNothingKnownStillGetsABadge() {
        assertEquals("?", textOf(badge(null, null)));
    }

    // ------------------------------------------------------------------
    // Colour
    // ------------------------------------------------------------------

    @Test
    void thesameAuthorAlwaysGetsTheSameColour() {
        assertEquals(fillOf(badge("Ada Lovelace", "ada@example.com")),
                fillOf(badge("Ada Lovelace", "ada@example.com")));
    }

    @Test
    void caseAndSpacingInTheAddressDoNotMakeASecondPerson() {
        assertEquals(fillOf(badge("Ada Lovelace", "ada@example.com")),
                fillOf(badge("Ada Lovelace", "  Ada@Example.com  ")),
                "one person committing under two spellings should keep one colour");
    }

    @Test
    void theBadgeMatchesTheColourTheGraphDrawsForThatAuthor() {
        // The column badge and the node on the lane have to agree, or the same person
        // appears in two colours on one row.
        Color fromGraph = Avatars.hue(Avatars.identityOf("Ada Lovelace", "ada@example.com"));
        assertEquals(fromGraph, fillOf(badge("Ada Lovelace", "ada@example.com")));
    }

    @Test
    void theInitialsAreLegibleAgainstTheDisc() {
        AuthorBadge b = badge("Ada Lovelace", "ada@example.com");
        Color fill = fillOf(b);
        Color text = FxHarness.call(() -> b.getChildrenUnmodifiable().stream()
                .filter(n -> n instanceof Label)
                .map(n -> (Color) ((Label) n).getTextFill())
                .findFirst().orElseThrow());

        assertEquals(GraphPalette.textOn(fill), text,
                "the text colour is chosen for contrast with the disc it sits on");
    }

    // ------------------------------------------------------------------
    // Shape
    // ------------------------------------------------------------------

    @Test
    void theBadgeIsBuiltFromNodesRatherThanAnImage() {
        AuthorBadge b = badge("Ada Lovelace", "ada@example.com");

        assertTrue(FxHarness.call(() -> b.getChildrenUnmodifiable().stream()
                        .noneMatch(n -> n instanceof javafx.scene.image.ImageView)),
                "an ImageView is a fixed grid of pixels, which is what the zoom made blocky");
        assertFalse(textOf(b).isBlank(), "the initials are real text, so they scale");
    }

    @Test
    void theBadgeIsSquareSoItsCornerRadiusReadsAsACircle() {
        AuthorBadge b = badge("Ada Lovelace", "ada@example.com");
        assertEquals(b.diameter(), FxHarness.call(b::getWidth), 0.5);
        assertEquals(b.diameter(), FxHarness.call(b::getHeight), 0.5);
    }

    @Test
    void clearingLeavesNothingDrawn() {
        AuthorBadge b = badge("Ada Lovelace", "ada@example.com");
        FxHarness.run(b::clearAuthor);

        assertNull(FxHarness.call(b::getBackground));
        assertEquals("", textOf(b));
    }
}
