package com.jfx.git.ui.syntax;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lexes lines through TextMate grammars, behind a cache.
 *
 * <p>Every language is a grammar: the registry ({@link GrammarRegistry}) maps a file's
 * extension to its bundled {@code .tmLanguage.json}, and this class runs lines through
 * it. There used to be three hand-written lexers claiming Java, Python and JSON ahead
 * of the registry; they are gone — two implementations of one feature meant two places
 * to be wrong, and the grammars are better at their languages than a hand lexer can be.
 *
 * <p>One guard keeps this off the critical path: lexed lines are cached, keyed by the
 * line and the grammar state it was lexed from, because a diff shows the same context
 * lines repeatedly and switching between File view and Diff view re-lexes the same
 * content. Above {@link #MAX_LINES} no reader is scanning by eye anyway — highlighting
 * is skipped outright rather than made the reason a large file takes seconds to open.
 */
public final class Highlighter {

    /**
     * Above this many lines the cost stops being worth it, for a caller that lexes the
     * whole document before showing any of it; see {@link #refusal}.
     */
    public static final int MAX_LINES = 6000;

    /**
     * Above this many characters in all, likewise.
     *
     * <p>A line count alone let a minified file through: one line, so well under
     * {@link #MAX_LINES}, and a megabyte long.
     */
    public static final int MAX_CHARS = 1_000_000;

    /**
     * The line bound for a caller that colours in chunks, visible rows first; see
     * {@link #refusalForChunked}.
     *
     * <p>The diff viewer's cap was {@link #MAX_LINES} while it lexed the whole document
     * before any colour showed, which made the cap the difference between a file opening
     * and a file hanging. Coloured a screen first and the rest in chunks behind it, a long
     * file is readable at once whatever its length, and the cap only bounds the total
     * background work - a generated file of a few hundred thousand lines is still not
     * worth a minute of the lane.
     */
    public static final int MAX_LINES_CHUNKED = 100_000;

    /**
     * The character bound to go with it. The minified-file case is caught line by line
     * ({@link #MAX_LINE_LENGTH}); this bounds the total, and 100,000 lines of ordinary
     * source run to about five megabytes.
     */
    public static final int MAX_CHARS_CHUNKED = 8_000_000;

    /**
     * A line longer than this is left uncoloured, and the lexer starts afresh after it.
     *
     * <p>TextMate grammars are regular expressions run along the line; on a minified line
     * of hundreds of kilobytes one can take seconds, and each is allowed half a second
     * before it gives up. Nothing is lost by not colouring a line nobody reads.
     */
    public static final int MAX_LINE_LENGTH = 5_000;

    /** Enough to cover a large file without the cache itself becoming the memory problem. */
    private static final int CACHE_LIMIT = 20_000;

    /**
     * Cache key for {@link #lexScoped}: a record gets correct equals/hashCode for free,
     * which matters here because the state is a grammar's own rule stack object, not an
     * {@code int} a string could represent faithfully.
     */
    private record ScopedCacheKey(String language, Object state, String line) {
    }

    private final Map<ScopedCacheKey, ScopedTokenizer.Lexed> scopedCache =
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<ScopedCacheKey, ScopedTokenizer.Lexed> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    /** The grammar-backed tokenizer for {@code path}'s extension; null when none claims it. */
    public static ScopedTokenizer tokenizerForPath(String path) {
        return GrammarRegistry.instance().tokenizerForPath(path);
    }

    /** Whether a document of {@code lines} lines is small enough to highlight. */
    public static boolean shouldHighlight(int lines) {
        return lines <= MAX_LINES;
    }

    /**
     * Why {@code lines} is too much to highlight, for the notice that says so, or null
     * when it is not.
     */
    public static String refusal(java.util.List<String> lines) {
        return refusal(lines, MAX_LINES, MAX_CHARS);
    }

    /** {@link #refusal}, under the bounds of a caller that colours in chunks. */
    public static String refusalForChunked(java.util.List<String> lines) {
        return refusal(lines, MAX_LINES_CHUNKED, MAX_CHARS_CHUNKED);
    }

    private static String refusal(java.util.List<String> lines, int maxLines, int maxChars) {
        if (lines.size() > maxLines) {
            return lines.size() + " lines";
        }
        long chars = 0;
        for (String line : lines) {
            chars += line == null ? 0 : line.length();
            if (chars > maxChars) {
                return "over " + (maxChars / 1_000_000) + " MB";
            }
        }
        return null;
    }

    /** Whether one line is short enough to lex; see {@link #MAX_LINE_LENGTH}. */
    public static boolean lexable(String line) {
        return line == null || line.length() <= MAX_LINE_LENGTH;
    }

    /**
     * Lexes one line through a grammar-backed tokenizer, reusing an earlier result for
     * the same line and state.
     */
    public synchronized ScopedTokenizer.Lexed lexScoped(ScopedTokenizer tokenizer, String line, Object state) {
        ScopedCacheKey key = new ScopedCacheKey(tokenizer.language(), state, line);
        ScopedTokenizer.Lexed cached = scopedCache.get(key);
        if (cached != null) {
            return cached;
        }
        ScopedTokenizer.Lexed lexed = tokenizer.lex(line, state);
        scopedCache.put(key, lexed);
        return lexed;
    }

    /** Drops everything cached; used when the viewer switches to a different file. */
    public synchronized void clear() {
        scopedCache.clear();
    }
}
