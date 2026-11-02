package com.jfx.git.ui.syntax;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loading and using every grammar prints nothing.
 *
 * <p>The app's console showed TM4E warnings on first use of Markdown ("CANNOT find
 * grammar for scopeName ... REMOVING BeginEndRule ... markup.fenced_code.block") and
 * joni's "character class has ']' without escape" from many grammars - about six
 * thousand across the catalogue. Unbundled embedded languages now get an empty grammar;
 * references to rules a grammar never defines are demoted to FINE, as VS Code ignores
 * them; joni's lint is dropped. This loads every registered language and tokenises a few
 * lines, with a handler on TM4E's loggers, and fails on anything else that would still
 * reach the console.
 */
class GrammarLoadWarningsTest {

    @Test
    void loadingAndUsingEveryGrammarLogsNoWarning() {
        Logger tm4e = Logger.getLogger("org.eclipse.tm4e");
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                // A logger's own filter runs before its handlers; this one only sees
                // what would really be printed.
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getLoggerName() + ": " + record.getMessage()
                            + " " + java.util.Arrays.toString(record.getParameters()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        tm4e.addHandler(capture);
        try {
            GrammarRegistry registry = GrammarRegistry.instance();
            for (LanguageDefinition language : registry.languages()) {
                ScopedTokenizer tokenizer = registry.tokenizerForLanguage(language.id());
                assertNotNull(tokenizer, language.id() + " did not load");
                Object state = tokenizer.initialState();
                for (String line : List.of("x = [1, 2] # ] \"s\" ", "```bibtex", "@a{b,", "```", "end")) {
                    state = tokenizer.lex(line, state).stateOut();
                }
            }
        } finally {
            tm4e.removeHandler(capture);
        }
        assertTrue(warnings.isEmpty(), () -> warnings.size() + " TM4E warnings, e.g.:\n  "
                + String.join("\n  ", warnings.stream().distinct().limit(15).toList()));
    }

    @Test
    void anUnbundledEmbeddedLanguageKeepsTheFenceAndLeavesTheContentPlain() {
        ScopedTokenizer markdown = GrammarRegistry.instance().tokenizerForPath("README.md");
        assertNotNull(markdown);
        // A language Markdown names for fences but nothing here bundles.
        Object state = markdown.initialState();
        ScopedTokenizer.Lexed open = markdown.lex("```gnuplot", state);
        boolean fenced = open.spans().stream().anyMatch(s -> s.styleClasses().stream()
                .anyMatch(c -> c.startsWith("tm-markup-fenced") || c.startsWith("tm-punctuation-definition-markdown")));
        assertTrue(fenced, "the opening fence is still recognised: " + open.spans());
    }
}
