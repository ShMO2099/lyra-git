package com.jfx.git.ui.syntax;

import java.util.List;

/**
 * Lexes source a line at a time using a grammar with an arbitrary scope taxonomy, rather
 * than the seven fixed {@link TokenType} values {@link Tokenizer} implementations use.
 *
 * <p>A grammar-backed token can carry several scopes at once - a JSON string's closing
 * quote is simultaneously {@code punctuation.definition.string.end}, {@code
 * string.quoted.double}, and {@code string} - and a theme should be able to colour any of
 * those three levels without the other two needing their own rule. So a span here carries
 * every class its scope implies, from most specific to least, and lets CSS's own cascade
 * pick which one a theme actually defines a rule for.
 *
 * <p>State is opaque ({@link Object}, not {@code int}) because a grammar's own idea of
 * "where the previous line left off" is a real object - a rule stack - not a small
 * integer. {@link #initialState()} exists because that starting value is not the same
 * for every implementation. {@link #threadsState()} exists because not every grammar's
 * state survives the round trip: see {@link LanguageDefinition#threadState}.
 */
public interface ScopedTokenizer {

    /** A run of characters within a line and every style class its scope implies. */
    record Span(int start, int end, List<String> styleClasses) {
    }

    /** The spans of one line and the state the next line starts in. */
    record Lexed(List<Span> spans, Object stateOut) {
    }

    /** The state a first line, or a line right after a hunk boundary, starts in. */
    Object initialState();

    Lexed lex(String line, Object state);

    /**
     * Whether the state returned by {@link #lex} should be fed into the next line.
     * When false, callers pass {@link #initialState()} for every line; the tokenizer
     * also guards this itself, so a caller that forgets is still correct.
     */
    default boolean threadsState() {
        return true;
    }

    /** Human-readable name, used when reporting why highlighting is off. */
    String language();
}
