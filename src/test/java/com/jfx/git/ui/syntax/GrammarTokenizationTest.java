package com.jfx.git.ui.syntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each bundled grammar must actually tokenise, not merely load.
 *
 * <p>The failure this guards against was found the hard way: the first Java grammar
 * bundled here (redhat-developer/vscode-java's) loads cleanly under TM4E but collapses
 * every line inside a class body into a single {@code meta.class.body} scope — no
 * keywords, no strings, no types. A test that only checked "the grammar loaded" passed.
 * So each language below asserts that representative tokens come out with the scope
 * family a reader needs: keywords, strings, comments, types.
 *
 * <p>A scope "family" is a prefix match on the dot-joined class list ({@code tm-storage}
 * matches {@code tm-storage-modifier-java}), because grammars legitimately differ in how
 * deep they nest. What must hold is the category, not the depth.
 */
class GrammarTokenizationTest {

    /** True when any of the run's style classes belongs to {@code family}. */
    private static boolean inFamily(ScopedTokenizer.Span span, String family) {
        // Per-class, not on a joined string: "tm-support,tm-support-function,...",
        // tested as one string, fails a startsWith("tm-support-function") probe at
        // the comma after the first, shorter class.
        for (String c : span.styleClasses()) {
            if (c.startsWith(family)) {
                return true;
            }
        }
        return false;
    }

    /** True when any span covering {@code needle} carries a scope in {@code family}. */
    private boolean tokenizesAs(ScopedTokenizer tokenizer, List<String> lines,
                                String needle, String family) {
        Object state = tokenizer.initialState();
        for (String line : lines) {
            ScopedTokenizer.Lexed lexed = tokenizer.lex(line, state);
            for (ScopedTokenizer.Span span : lexed.spans()) {
                String covered = line.substring(span.start(), Math.min(span.end(), line.length()));
                if (covered.contains(needle) && inFamily(span, family)) {
                    return true;
                }
            }
            state = lexed.stateOut();
        }
        return false;
    }

    private void assertTokenized(String path, String[] lines, Object[][] expectations) {
        ScopedTokenizer tokenizer = GrammarRegistry.instance().tokenizerForPath(path);
        assertNotNull(tokenizer, path + " must select a registered grammar");
        for (Object[] expectation : expectations) {
            String needle = (String) expectation[0];
            String family = (String) expectation[1];
            assertTrue(tokenizesAs(tokenizer, List.of(lines), needle, family),
                    () -> path + ": '" + needle + "' must be scoped as " + family
                            + " — if this fails, the grammar loads but does not tokenise");
        }
    }

    // ------------------------------------------------------------------
    // The languages the hand-written lexers used to serve — these are the
    // ones whose grammar quality matters most.
    // ------------------------------------------------------------------

    @Test
    void javaGrammarTokenisesKeywordsTypesAndStrings() {
        // Java's grammar is lexed line-independently (see LanguageDefinition.threadState),
        // so every expectation below holds per line, without cross-line context.
        assertTokenized("src/Main.java", new String[]{
                "public class Main {",
                "    static final int ANSWER = 42;",
                "    String s = \"hi\";",
                "}",
        }, new Object[][]{
                {"public", "tm-storage"},
                {"static", "tm-storage"},
                {"class", "tm-storage"},
                {"Main", "tm-entity-name"},
                {"int", "tm-storage-type"},
                {"42", "tm-constant-numeric"},
                {"hi", "tm-string-quoted"},
        });
    }

    @Test
    void pythonGrammarTokenisesKeywordsFunctionsAndStrings() {
        assertTokenized("app.py", new String[]{
                "def greet(name):",
                "    return name",
                "# done",
        }, new Object[][]{
                {"def", "tm-storage"},
                {"return", "tm-keyword"},
                {"done", "tm-comment"},
        });
    }

    @Test
    void jsonGrammarTokenisesKeysValuesAndPunctuation() {
        assertTokenized("config.json", new String[]{
                "{",
                "  \"name\": \"lyra\",",
                "  \"enabled\": true",
                "}",
        }, new Object[][]{
                {"true", "tm-constant-language"},
        });
    }

    // ------------------------------------------------------------------
    // A sample of the registry-only languages, proving the whole registry
    // stays live rather than just the three replacements above.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "'run.sh', shell, 'echo', tm-support-function",
            "'lib/impl.cc', cpp, 'int', tm-storage-type",
            "'main.go', go, 'func', tm-keyword-function",
            "'index.js', javascript, 'return', tm-keyword-control",
            "'App.swift', swift, 'print', tm-support-function",
            "'pom.xml', xml, 'name', tm-entity-name-tag",
            "'build.yml', yaml, 'x', tm-string-unquoted",
    })
    void everyRegisteredLanguageActuallyTokenises(String path, String id,
                                                  String needle, String family) {
        ScopedTokenizer tokenizer = GrammarRegistry.instance().tokenizerForPath(path);
        assertNotNull(tokenizer, path + " (" + id + ") must select a grammar");
        assertTrue(
                tokenizesSomewhereAs(tokenizer, sampleFor(path), needle, family),
                () -> path + " (" + id + "): '" + needle + "' must be scoped as " + family
                        + " — if this fails, the grammar loads but does not tokenise");
    }

    /** One representative line-set per registered language. */
    private static List<String> sampleFor(String path) {
        return switch (path) {
            case "run.sh" -> List.of("#!/bin/bash", "echo \"hi there\"");
            case "lib/impl.cc" -> List.of("int main() {", "  return 0;", "}");
            case "main.go" -> List.of("func main() {", "  println(\"hi\")", "}");
            case "index.js" -> List.of("function f(a) {", "  return a;", "}");
            case "App.swift" -> List.of("struct S {", "  func f() { print(1) }", "}");
            case "pom.xml" -> List.of("<project>", "  <name>x</name>", "</project>");
            case "build.yml" -> List.of("version: 2", "  name: x", "enabled: true");
            default -> throw new IllegalArgumentException("no sample for " + path);
        };
    }

    /** True when any span whose text contains {@code needle} carries {@code family}. */
    private boolean tokenizesSomewhereAs(ScopedTokenizer tokenizer, List<String> lines,
                                         String needle, String family) {
        Object state = tokenizer.initialState();
        for (String line : lines) {
            ScopedTokenizer.Lexed lexed = tokenizer.lex(line, state);
            for (ScopedTokenizer.Span span : lexed.spans()) {
                String covered = line.substring(span.start(), Math.min(span.end(), line.length()));
                if (covered.contains(needle) && inFamily(span, family)) {
                    return true;
                }
            }
            state = lexed.stateOut();
        }
        return false;
    }

    /**
     * Lines that came out with no tokens at all while their grammar carried a look-behind
     * the joni engine rejects: TM4E aborts the whole line when one of the rules it tries
     * will not compile. The grammars are repaired (grammars/NOTICE.md and the catalogue's
     * NOTICE.txt); {@link GrammarPatternsCompileTest} stops the patterns coming back, and
     * these are the lines a user saw go plain.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "a.ts  | const x: number = 1;    | const  | tm-storage",
            "a.ts  | import { a } from \"b\"; | from   | tm-keyword",
            "a.ts  | using res = open();     | using  | tm-storage",
            "a.js  | const x = 1;            | const  | tm-storage",
            "a.js  | import a from \"b\";     | import | tm-keyword",
            "a.tsx | const x = 1;            | const  | tm-storage",
            "a.jsx | import a from \"b\";     | import | tm-keyword",
            "a.rs  | fn main() { let x = 1; } | let   | tm-storage",
            "a.rs  | use std::io::stdin;     | std    | tm-entity",
    })
    void linesJoniUsedToAbortAreHighlighted(String path, String line, String needle, String family) {
        ScopedTokenizer tokenizer = GrammarRegistry.instance().tokenizerForPath(path);
        assertNotNull(tokenizer, path + " must select a grammar");
        assertTrue(tokenizesSomewhereAs(tokenizer, List.of(line), needle, family),
                () -> path + ": '" + needle + "' in '" + line + "' must be scoped as " + family);
    }

    @Test
    void unknownExtensionsStayUnhighlighted() {
        assertFalse(GrammarRegistry.instance().tokenizerForPath("blob.unknownext") != null,
                "no grammar claims it, and guessing would be worse than plain text");
    }
}
