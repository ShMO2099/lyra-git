package com.jfx.git.ui.syntax;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.Registry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextMateTokenizerTest {

    // Copied verbatim from TM4E's own upstream test fixture
    // (first-mate/fixtures/hello.json), not freehanded - see Tm4eSpikeTest for the
    // same rationale.
    private static final String HELLO_GRAMMAR = """
            {
            \t"name": "hello",
            \t"scopeName": "source.hello",
            \t"fileTypes": [ "world" ],
            \t"patterns": [
            \t\t{ "match": "hello", "name": "prefix.hello" },
            \t\t{
            \t\t\t"match": "world(!?)",
            \t\t\t"captures": { "1": { "name": "emphasis.hello" } },
            \t\t\t"name": "suffix.hello"
            \t\t}
            \t]
            }
            """;

    private static TextMateTokenizer helloTokenizer() {
        Registry registry = new Registry();
        IGrammar grammar = registry.addGrammar(
                IGrammarSource.fromString(IGrammarSource.ContentType.JSON, HELLO_GRAMMAR));
        return new TextMateTokenizer("Hello", grammar, true);
    }

    @Test
    void languageReturnsWhatItWasConstructedWith() {
        assertEquals("Hello", helloTokenizer().language());
    }

    @Test
    void initialStateIsNull() {
        assertNull(helloTokenizer().initialState());
    }

    @Test
    void lexesRealTokensWithExpectedStyleClasses() {
        TextMateTokenizer tokenizer = helloTokenizer();
        ScopedTokenizer.Lexed lexed = tokenizer.lex("hello world!", tokenizer.initialState());

        assertFalse(lexed.spans().isEmpty());
        ScopedTokenizer.Span first = lexed.spans().get(0);
        assertEquals(0, first.start());
        assertEquals(5, first.end());
        assertTrue(first.styleClasses().contains("tm-prefix-hello"),
                "expected tm-prefix-hello in " + first.styleClasses());

        ScopedTokenizer.Span last = lexed.spans().get(lexed.spans().size() - 1);
        assertTrue(last.styleClasses().contains("tm-emphasis-hello"),
                "expected tm-emphasis-hello on the final token, got " + last.styleClasses());
    }

    @Test
    void stateThreadsAcrossConsecutiveLines() {
        TextMateTokenizer tokenizer = helloTokenizer();
        ScopedTokenizer.Lexed firstLine = tokenizer.lex("hello", tokenizer.initialState());
        assertNotNull(firstLine.stateOut(), "TM4E should hand back a real rule stack, not null");

        // Threading the returned state into the next line must not throw, and must
        // produce a real result - this is the exact call DiffViewer/Highlighter make
        // between consecutive rows of a hunk.
        ScopedTokenizer.Lexed secondLine = tokenizer.lex("world!", firstLine.stateOut());
        assertFalse(secondLine.spans().isEmpty());
    }

    // ------------------------------------------------------------------
    // styleClassesFor - the scope -> CSS class chain, in isolation
    // ------------------------------------------------------------------

    @Test
    void oneComponentScopeProducesOneClass() {
        assertEquals(List.of("tm-source"), TextMateTokenizer.styleClassesFor(List.of("source")));
    }

    @Test
    void dottedScopeProducesEveryPrefixBroadToNarrow() {
        assertEquals(
                List.of("tm-string", "tm-string-quoted", "tm-string-quoted-double",
                        "tm-string-quoted-double-json"),
                TextMateTokenizer.styleClassesFor(List.of("source.json", "string.quoted.double.json")));
    }

    @Test
    void onlyTheInnermostScopeIsUsedNotTheWholeStack() {
        // The outer "source.json" scope present on every token in a JSON file must not
        // add tm-source/tm-source-json noise to every single span.
        List<String> classes = TextMateTokenizer.styleClassesFor(
                List.of("source.json", "meta.structure.dictionary.json", "string.quoted.double.json"));
        assertFalse(classes.contains("tm-source"));
        assertFalse(classes.contains("tm-meta"));
        assertTrue(classes.contains("tm-string-quoted-double-json"));
    }

    @Test
    void emptyScopesProduceNoClasses() {
        assertEquals(List.of(), TextMateTokenizer.styleClassesFor(List.of()));
    }
}
