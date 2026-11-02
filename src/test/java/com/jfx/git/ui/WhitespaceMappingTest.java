package com.jfx.git.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The display text is what the user sees; the mapping is what they get when they copy.
 * These two must never drift, or copying a diff silently substitutes markers for the
 * spaces and tabs that were selected.
 */
class WhitespaceMappingTest {

    @Test
    void identityLeavesTextAlone() {
        WhitespaceMapping mapping = WhitespaceMapping.identity("  int x = 1;");
        assertEquals("  int x = 1;", mapping.display());
        assertEquals("  int x = 1;", mapping.source());
        assertEquals(3, mapping.sourceOffset(3));
    }

    @Test
    void spacesBecomeMarkersOneForOne() {
        WhitespaceMapping mapping = WhitespaceMapping.visible("a b");
        assertEquals("a·b", mapping.display());
        assertEquals("a b", mapping.source());
        assertEquals(0, mapping.sourceOffset(0));
        assertEquals(1, mapping.sourceOffset(1));
        assertEquals(2, mapping.sourceOffset(2));
    }

    @Test
    void tabsExpandAndStillMapBack() {
        WhitespaceMapping mapping = WhitespaceMapping.visible("\tx");
        assertEquals("→   x", mapping.display());
        // Every column inside the expanded tab belongs to the single source tab.
        assertEquals(0, mapping.sourceOffset(0));
        assertEquals(0, mapping.sourceOffset(1));
        assertEquals(0, mapping.sourceOffset(3));
        assertEquals(1, mapping.sourceOffset(4));
        assertEquals(2, mapping.sourceOffset(5));
    }

    @Test
    void mixedIndentationMapsEndOfLine() {
        WhitespaceMapping mapping = WhitespaceMapping.visible("\t  a");
        assertEquals("→   ··a", mapping.display());
        assertEquals(4, mapping.sourceOffset(mapping.display().length()));
    }

    @Test
    void offsetsOutsideTheLineAreClamped() {
        WhitespaceMapping mapping = WhitespaceMapping.visible("ab");
        assertEquals(0, mapping.sourceOffset(-5));
        assertEquals(2, mapping.sourceOffset(99));
    }

    @Test
    void nullLineIsEmpty() {
        assertEquals("", WhitespaceMapping.visible(null).display());
        assertEquals("", WhitespaceMapping.identity(null).source());
    }

    @Test
    void selectingAnIndentYieldsRealWhitespace() {
        // The bug this guards: copying "    x" with markers on used to paste "····x".
        WhitespaceMapping mapping = WhitespaceMapping.visible("    x");
        String selected = mapping.source().substring(
                mapping.sourceOffset(0), mapping.sourceOffset(4));
        assertEquals("    ", selected);
    }
}
