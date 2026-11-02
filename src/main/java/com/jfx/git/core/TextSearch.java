package com.jfx.git.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Finding text in a document, and replacing it: the part of a find bar that has nothing
 * to do with a screen.
 *
 * <p>Three ways to read a query, as every editor offers them: case-sensitive or not,
 * whole words only or not, and as a regular expression or as the literal text typed. A
 * literal query is quoted before it becomes a pattern, so {@code a.b} finds "a.b" and
 * not "axb" - the mistake a search box that "supports regex" quietly makes for everybody
 * who did not ask for it.
 *
 * <p>Matches are never empty. A pattern that can match nothing - {@code ^}, {@code x*} -
 * finds only its non-empty matches: an empty one is nothing a person could see, and
 * stepping over it one character at a time is how a search loops forever.
 */
public final class TextSearch {

    /** More than this many matches are not counted further; the bar says "1000+". */
    public static final int MAX_MATCHES = 1000;

    private TextSearch() {
    }

    /** How a query is read. */
    public record Options(boolean matchCase, boolean wholeWord, boolean regex) {

        public static final Options PLAIN = new Options(false, false, false);
    }

    /** One match: {@code start} inclusive, {@code end} exclusive, as {@code String.substring} takes them. */
    public record Match(int start, int end) {
    }

    /**
     * What a search found.
     *
     * @param error  why the query could not be used - an unfinished regular expression -
     *               or {@code null}
     * @param capped whether there were more than {@link #MAX_MATCHES} and the rest were not looked for
     */
    public record Result(List<Match> matches, String error, boolean capped) {

        static final Result NONE = new Result(List.of(), null, false);
    }

    /** {@code text} after a replace-all, and how many matches it replaced. */
    public record Replaced(String text, int count) {
    }

    /** The pattern a query means under {@code options}. */
    static Pattern compile(String query, Options options) {
        String body = options.regex() ? query : Pattern.quote(query);
        if (options.wholeWord()) {
            // Word boundaries that also hold at the edge of a match starting or ending in
            // punctuation: "\b" alone would make whole-word "foo()" never match anything.
            body = "(?<![\\p{L}\\p{N}_])(?:" + body + ")(?![\\p{L}\\p{N}_])";
        }
        int flags = Pattern.MULTILINE;
        if (!options.matchCase()) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return Pattern.compile(body, flags);
    }

    /** Every match of {@code query} in {@code text}, in order, up to {@link #MAX_MATCHES}. */
    public static Result find(String text, String query, Options options) {
        if (text == null || query == null || query.isEmpty()) {
            return Result.NONE;
        }
        Pattern pattern;
        try {
            pattern = compile(query, options);
        } catch (PatternSyntaxException e) {
            return new Result(List.of(), describe(e), false);
        }
        List<Match> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        int from = 0;
        while (from <= text.length() && matcher.find(from)) {
            if (matcher.end() == matcher.start()) {
                from = matcher.end() + 1;
                continue;
            }
            if (matches.size() == MAX_MATCHES) {
                return new Result(List.copyOf(matches), null, true);
            }
            matches.add(new Match(matcher.start(), matcher.end()));
            from = matcher.end();
        }
        return new Result(List.copyOf(matches), null, false);
    }

    /**
     * What the match at {@code match} is replaced with: {@code replacement} as typed for a
     * literal query; for a regular expression, with {@code $1}, {@code ${name}} and
     * {@code \} escapes filled in from that match - found again in the whole text, so a
     * look-behind sees what is behind it.
     */
    public static String replacementFor(String text, Match match, String query, String replacement,
                                        Options options) {
        String with = replacement == null ? "" : replacement;
        if (!options.regex()) {
            return with;
        }
        try {
            Matcher matcher = compile(query, options).matcher(text);
            if (matcher.find(match.start()) && matcher.start() == match.start()) {
                return expand(matcher, with);
            }
        } catch (PatternSyntaxException | IndexOutOfBoundsException ignored) {
            // Fall through: the replacement as typed.
        }
        return with;
    }

    /**
     * Everything in {@code text} replaced at once, and how many places that was.
     *
     * <p>Not capped: a cap on what is highlighted is no reason to leave the 1001st
     * occurrence alone when asked for all of them.
     */
    public static Replaced replaceAll(String text, String query, String replacement, Options options) {
        if (text == null || query == null || query.isEmpty()) {
            return new Replaced(text, 0);
        }
        Matcher matcher;
        try {
            matcher = compile(query, options).matcher(text);
        } catch (PatternSyntaxException e) {
            return new Replaced(text, 0);
        }
        String with = replacement == null ? "" : replacement;
        StringBuilder out = new StringBuilder(text.length());
        int count = 0;
        int copied = 0;
        int from = 0;
        while (from <= text.length() && matcher.find(from)) {
            if (matcher.end() == matcher.start()) {
                from = matcher.end() + 1;
                continue;
            }
            out.append(text, copied, matcher.start());
            out.append(options.regex() ? expand(matcher, with) : with);
            copied = matcher.end();
            from = matcher.end();
            count++;
        }
        if (count == 0) {
            return new Replaced(text, 0);
        }
        out.append(text, copied, text.length());
        return new Replaced(out.toString(), count);
    }

    /**
     * A replacement with its group references filled in from {@code match}: {@code $n},
     * {@code ${name}}, and a backslash taking the next character literally - the syntax
     * of {@code Matcher.appendReplacement}, without its bookkeeping. A reference to a
     * group that does not exist is left as written.
     */
    static String expand(MatchResult match, String replacement) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\' && i + 1 < replacement.length()) {
                out.append(replacement.charAt(++i));
            } else if (c == '$' && i + 1 < replacement.length()) {
                char next = replacement.charAt(i + 1);
                if (Character.isDigit(next)) {
                    int group = next - '0';
                    if (group <= match.groupCount()) {
                        String value = match.group(group);
                        out.append(value == null ? "" : value);
                        i++;
                        continue;
                    }
                } else if (next == '{') {
                    int close = replacement.indexOf('}', i + 2);
                    if (close > 0) {
                        String name = replacement.substring(i + 2, close);
                        try {
                            String value = match.group(name);
                            out.append(value == null ? "" : value);
                            i = close;
                            continue;
                        } catch (IllegalArgumentException | UnsupportedOperationException noSuchGroup) {
                            // Left as written, below.
                        }
                    }
                }
                out.append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** A regular expression's mistake in a sentence, without Java's caret diagram. */
    private static String describe(PatternSyntaxException e) {
        String description = e.getDescription();
        if (description == null || description.isBlank()) {
            return "Not a valid regular expression";
        }
        String first = description.substring(0, 1).toUpperCase(Locale.ROOT) + description.substring(1);
        return first + (e.getIndex() >= 0 ? " at character " + (e.getIndex() + 1) : "");
    }
}
