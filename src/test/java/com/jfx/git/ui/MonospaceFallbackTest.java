package com.jfx.git.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The rules {@link MonospaceFallback} answers, read from the stylesheets' text. */
class MonospaceFallbackTest {

    @Test
    void aRuleNamingConsolasFirstGetsTheSubstituteWithTheSameSelectors() {
        String css = """
                /* { a comment with braces } */
                .hash, .reflog-ids:hover {
                    -fx-font-family: 'Consolas', 'JetBrains Mono', monospace;
                    -fx-font-size: 11px;
                }
                .label { -fx-font-family: 'Segoe UI'; }
                .plain { -fx-text-fill: red; }
                """;

        assertEquals(".hash, .reflog-ids:hover {\n    -fx-font-family: monospace;\n}\n",
                MonospaceFallback.overrides(css, "monospace"));
    }

    @Test
    void aStylesheetWithoutConsolasNeedsNothing() {
        assertEquals("", MonospaceFallback.overrides(".a { -fx-font-size: 2em; }", "monospace"));
    }

    /**
     * Every Consolas rule in the real stylesheets is answered, one override each. Counted
     * by rule, not by declaration: a rule may name the family twice, as
     * {@code .diff-line-number} does.
     */
    @Test
    void everyConsolasRuleInTheApplicationsStylesheetsIsCovered() throws Exception {
        Pattern declaration = Pattern.compile("-fx-font-family\\s*:\\s*'Consolas'");
        for (String resource : new String[]{"/css/components.css", "/css/lyra.css", "/diff-styles.css"}) {
            String css;
            try (InputStream in = MonospaceFallbackTest.class.getResourceAsStream(resource)) {
                assertNotNull(in, resource);
                css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            long declared = java.util.Arrays.stream(css.split("}"))
                    .filter(rule -> declaration.matcher(rule).find()).count();
            long answered = Pattern.compile("-fx-font-family: monospace;")
                    .matcher(MonospaceFallback.overrides(css, "monospace")).results().count();
            assertTrue(declared > 0, resource + " names Consolas; the test would prove nothing");
            assertEquals(declared, answered, resource);
        }
    }
}
