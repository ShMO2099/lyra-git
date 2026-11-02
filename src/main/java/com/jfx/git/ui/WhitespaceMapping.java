package com.jfx.git.ui;

/**
 * One line of text with its whitespace made visible, plus the map back to the original.
 *
 * <p>Substituting markers into the buffer is the only way to draw spaces and tabs in a
 * {@code StyleClassedTextArea}, but it means the buffer no longer holds what the user
 * selected. {@link #sourceOffset(int)} converts a display column back so a copy hands
 * over real spaces and tabs rather than the markers standing in for them.
 */
final class WhitespaceMapping {

    static final String SPACE_MARKER = "·";
    static final String TAB_MARKER = "→   ";

    private final String source;
    private final String display;
    /** {@code map[i]} is the source offset display offset {@code i} came from. */
    private final int[] map;

    private WhitespaceMapping(String source, String display, int[] map) {
        this.source = source;
        this.display = display;
        this.map = map;
    }

    /** Leaves the text alone; display and source offsets are the same. */
    static WhitespaceMapping identity(String source) {
        String text = source == null ? "" : source;
        return new WhitespaceMapping(text, text, null);
    }

    /** Replaces spaces and tabs with visible markers, recording where each came from. */
    static WhitespaceMapping visible(String source) {
        String text = source == null ? "" : source;
        StringBuilder display = new StringBuilder(text.length());
        int[] map = new int[displayLength(text) + 1];

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String marker = ch == ' ' ? SPACE_MARKER : ch == '\t' ? TAB_MARKER : String.valueOf(ch);
            for (int m = 0; m < marker.length(); m++) {
                map[display.length() + m] = i;
            }
            display.append(marker);
        }
        map[display.length()] = text.length();
        return new WhitespaceMapping(text, display.toString(), map);
    }

    private static int displayLength(String text) {
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            length += text.charAt(i) == '\t' ? TAB_MARKER.length() : 1;
        }
        return length;
    }

    String source() {
        return source;
    }

    String display() {
        return display;
    }

    /** The source offset for a display column, clamped to the line's own bounds. */
    int sourceOffset(int displayColumn) {
        if (map == null) {
            return Math.max(0, Math.min(displayColumn, source.length()));
        }
        int clamped = Math.max(0, Math.min(displayColumn, map.length - 1));
        return map[clamped];
    }

    /**
     * The display column a source offset landed at.
     *
     * <p>The inverse direction, needed when something computed against the original text
     * - an intra-line highlight, say - has to be drawn against the substituted text.
     */
    int displayOffset(int sourceOffset) {
        int clamped = Math.max(0, Math.min(sourceOffset, source.length()));
        if (map == null) {
            return clamped;
        }
        if (clamped == source.length()) {
            return display.length();
        }
        // The first display column mapping to this source offset is its start; markers
        // wider than the character they stand for occupy the columns after it.
        for (int i = 0; i < map.length; i++) {
            if (map[i] >= clamped) {
                return i;
            }
        }
        return display.length();
    }
}
