package com.jfx.git.core;

import com.jfx.git.core.TextSearch.Match;
import com.jfx.git.core.TextSearch.Options;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding and replacing text, without a screen. */
class TextSearchTest {

    private static final Options CASE = new Options(true, false, false);
    private static final Options WORD = new Options(false, true, false);
    private static final Options REGEX = new Options(false, false, true);
    private static final Options REGEX_CASE = new Options(true, false, true);

    private static List<Match> find(String text, String query, Options options) {
        TextSearch.Result result = TextSearch.find(text, query, options);
        assertNull(result.error(), () -> "unexpected error: " + result.error());
        return result.matches();
    }

    @Test
    void aLiteralQueryIsTheTextTypedNotAPattern() {
        assertEquals(List.of(new Match(4, 7)), find("axb a.b", "a.b", Options.PLAIN),
                "the dot is a dot, so \"axb\" is not a match");
        assertEquals(List.of(new Match(0, 3)), find("(x)", "(x)", Options.PLAIN),
                "brackets typed are brackets found, not an unfinished group");
    }

    @Test
    void caseIsIgnoredUnlessAskedFor() {
        assertEquals(2, find("Main main", "main", Options.PLAIN).size());
        assertEquals(List.of(new Match(5, 9)), find("Main main", "main", CASE));
        assertEquals(2, find("Été été", "été", Options.PLAIN).size(),
                "Unicode letters fold too, not only ASCII");
    }

    @Test
    void wholeWordsStopAtLettersDigitsAndUnderscores() {
        assertEquals(List.of(new Match(0, 3)), find("foo food foo_bar foo1", "foo", WORD));
        assertEquals(List.of(new Match(5, 10)), find("xfoo foo() foo()x", "foo()", WORD),
                "a query ending in punctuation still matches as a whole word - and only as one");
    }

    @Test
    void aRegularExpressionIsUsedAsOne() {
        assertEquals(List.of(new Match(0, 3), new Match(4, 7)), find("abc a1c", "a.c", REGEX));
        assertEquals(List.of(new Match(4, 7)), find("abc\ndef", "^def", REGEX),
                "^ means the start of a line, as an editor's search means it");
    }

    @Test
    void anUnfinishedExpressionIsReportedNotThrown() {
        TextSearch.Result result = TextSearch.find("text", "(unclosed", REGEX);
        assertTrue(result.matches().isEmpty());
        assertNotNull(result.error());
        assertFalse(result.error().contains("\n"), "one line, without Java's caret diagram");
        assertTrue(Character.isUpperCase(result.error().charAt(0)), result.error());
    }

    @Test
    void emptyMatchesAreSkippedAndTheSearchEnds() {
        assertEquals(List.of(new Match(1, 3), new Match(4, 5)), find("axxbx", "x*", REGEX),
                "only the matches a person could see");
        assertTrue(find("abc", "^", REGEX).isEmpty(), "a pattern matching only nothing finds nothing");
    }

    @Test
    void noQueryFindsNothing() {
        assertTrue(find("text", "", Options.PLAIN).isEmpty());
        assertTrue(find("text", null, Options.PLAIN).isEmpty());
        assertTrue(find(null, "x", Options.PLAIN).isEmpty());
    }

    @Test
    void manyMatchesAreCappedAndSaySo() {
        String text = "a".repeat(TextSearch.MAX_MATCHES + 5);
        TextSearch.Result result = TextSearch.find(text, "a", Options.PLAIN);
        assertEquals(TextSearch.MAX_MATCHES, result.matches().size());
        assertTrue(result.capped());
        assertFalse(TextSearch.find("aaa", "a", Options.PLAIN).capped());
    }

    @Test
    void replaceAllIsNotCapped() {
        String text = "a".repeat(TextSearch.MAX_MATCHES + 5);
        TextSearch.Replaced replaced = TextSearch.replaceAll(text, "a", "b", Options.PLAIN);
        assertEquals(TextSearch.MAX_MATCHES + 5, replaced.count());
        assertEquals("b".repeat(TextSearch.MAX_MATCHES + 5), replaced.text());
    }

    @Test
    void aLiteralReplacementIsTakenAsTyped() {
        TextSearch.Replaced replaced = TextSearch.replaceAll("cost: 5", "5", "$1.00", Options.PLAIN);
        assertEquals("cost: $1.00", replaced.text(),
                "a dollar sign in a plain replacement is money, not a group");
        assertEquals(1, replaced.count());
    }

    @Test
    void aRegexReplacementFillsInGroups() {
        assertEquals("b=a, d=c", TextSearch.replaceAll("a=b, c=d", "(\\w)=(\\w)", "$2=$1", REGEX).text());
        assertEquals("[x]", TextSearch.replaceAll("x", "(?<v>x)", "[${v}]", REGEX).text());
        assertEquals("$1", TextSearch.replaceAll("x", "x", "\\$1", REGEX).text(),
                "a backslash takes the next character literally");
        assertEquals("$9", TextSearch.replaceAll("x", "x", "$9", REGEX).text(),
                "a group that does not exist is left as written");
    }

    @Test
    void replacingNothingLeavesTheTextAlone() {
        TextSearch.Replaced replaced = TextSearch.replaceAll("abc", "z", "y", Options.PLAIN);
        assertEquals("abc", replaced.text());
        assertEquals(0, replaced.count());
        assertEquals(0, TextSearch.replaceAll("abc", "(", "y", REGEX).count(),
                "an unfinished expression replaces nothing");
    }

    @Test
    void oneReplacementIsWorkedOutFromItsOwnMatch() {
        String text = "x1 x2";
        Match second = find(text, "x(\\d)", REGEX).get(1);
        assertEquals("<2>", TextSearch.replacementFor(text, second, "x(\\d)", "<$1>", REGEX));
        assertEquals("<$1>", TextSearch.replacementFor(text, second, "x2", "<$1>", Options.PLAIN));
        // A look-behind needs the text before the match, which is why it is found again in
        // the whole text rather than in the match alone.
        String behind = "ab xb";
        Match afterX = find(behind, "(?<=x)b", REGEX_CASE).get(0);
        assertEquals("B", TextSearch.replacementFor(behind, afterX, "(?<=x)b", "B", REGEX_CASE));
    }

    @Test
    void expandMatchesTheJdkForWhatBothSupport() {
        Matcher matcher = Pattern.compile("(a)(b)?").matcher("a");
        assertTrue(matcher.find());
        assertEquals("[a][]", TextSearch.expand(matcher, "[$1][$2]"),
                "a group that did not take part is empty, as appendReplacement has it");
    }
}
