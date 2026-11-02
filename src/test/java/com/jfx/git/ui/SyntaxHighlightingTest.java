package com.jfx.git.ui;

import com.jfx.git.ui.syntax.Highlighter;

import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Syntax highlighting, end to end.
 *
 * <p>Two halves: the pipeline really applies token classes to the text surface, and the
 * stylesheet's token rules are specific enough to survive the base text-colour rule.
 * The second half exists because of the first half's history — the lexer was correct
 * for months while {@code .code-area .text { -fx-fill }} (two classes) silently beat
 * {@code .syntax-keyword { -fx-fill }} (one class) and painted every token back into
 * the default foreground. Nothing about that failure is visible in the model, which is
 * why both halves are pinned here.
 */
class SyntaxHighlightingTest {

    /**
     * Closes the off-screen stages this class opened.
     *
     * <p>Not housekeeping. A stage left showing goes on taking part in focus and layout
     * for the rest of the run, and the failures that causes land in whichever robot test
     * happens to follow - a click that reaches nothing, a dialog that "never opened".
     */
    @org.junit.jupiter.api.AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    // ------------------------------------------------------------------
    // Pipeline: tokens reach the text surface
    // ------------------------------------------------------------------

    @Test
    void aJavaDiffCarriesTokenStyleClassesOnTheRightCharacters() {
        FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, 900, 600);
            String diff = """
                    @@ -1,4 +1,4 @@
                     public class Main {
                    -    String name = "old";
                    +    String name = "new";
                         // comment
                     }
                    """;
            viewer.setFileDiff("src/Main.java", diff);
            stage.setUserData("keep-alive");
            FxHarness.pulse(viewer);
            DiffTextArea area =
                    (DiffTextArea) FxHarness.lookupAll(viewer, "code-area").get(0);

            assertTrue(Highlighter.tokenizerForPath("src/Main.java") != null,
                    "a .java path must select a grammar-backed tokenizer");

            var spans = area.getStyleSpans(0, area.getLength());
            String text = area.getText();

            // Walked as spans rather than offsets, so a change to row scaffolding
            // cannot silently unhook this test from what a user sees. Each styled run
            // is checked against the grammar role its text should play.
            boolean keywordOnPublic = false;
            boolean typeOnMain = false;
            boolean stringOnOld = false;
            boolean inlineDiffLayersWithGrammar = false;
            boolean commentOnSlashSlash = false;
            int pos = 0;
            for (var span : spans) {
                String styles = String.join(",", span.getStyle());
                String covered = text.substring(pos, pos + span.getLength()).trim();
                if (covered.equals("public") || covered.equals("class")) {
                    keywordOnPublic |= styles.contains("tm-storage");
                }
                if (covered.equals("Main")) {
                    typeOnMain = styles.contains("tm-entity-name-type");
                }
                if (covered.equals("old")) {
                    stringOnOld = styles.contains("tm-string");
                    inlineDiffLayersWithGrammar = styles.contains("diff-inline-delete");
                }
                // The grammar splits the marker from the text: "//" is punctuation,
                // the words after it are the comment proper.
                if (covered.contains("comment")) {
                    commentOnSlashSlash |= styles.startsWith("tm-comment");
                }
                pos += span.getLength();
            }

            int dbgPos = 0;
            for (var span : spans) {
                if (!span.getStyle().isEmpty()) {
                    System.out.println("[DIAG] '" + text.substring(dbgPos, dbgPos + span.getLength())
                            + "' <- " + span.getStyle());
                }
                dbgPos += span.getLength();
            }
            assertTrue(keywordOnPublic,
                    "'public' and 'class' must be coloured as storage keywords");
            assertTrue(typeOnMain, "'Main' must be coloured as a type name");
            assertTrue(stringOnOld, "the quoted word must be coloured as a string");
            assertTrue(inlineDiffLayersWithGrammar,
                    "the word-diff tint must layer on top of the grammar colour");
            assertTrue(commentOnSlashSlash, "the // comment must be coloured as one");
            return null;
        });
    }

    @Test
    void aGrammarBackedTokenizerIsSelectedForEachHighlightedExtension() {
        // Every extension the hand-written lexers used to claim must still highlight,
        // now through its grammar.
        for (String path : new String[]{"src/Main.java", "app.py", "data.json",
                "lib/impl.cc", "src/x.cs", "main.go", "lib.rs", "App.swift",
                "index.js", "index.tsx"}) {
            assertTrue(Highlighter.tokenizerForPath(path) != null,
                    path + " must select a grammar-backed tokenizer");
        }
        assertFalse(Highlighter.tokenizerForPath("notes.unknownext") != null,
                "an unknown extension gets no highlighting, not a wrong guess");
    }

    // ------------------------------------------------------------------
    // Stylesheet: token rules must win the cascade
    // ------------------------------------------------------------------

    /** A selector line containing a token class. */
    private static final Pattern TOKEN_SELECTOR =
            Pattern.compile("^([^\\{}/]*\\.(?:syntax|tm)-[\\w-]+[^\\{}/]*)\\{",
                    Pattern.MULTILINE);

    @Test
    void everyTokenSelectorIsScopedToBeatTheBaseTextRule() throws Exception {
        String css = readDiffStyles();
        Matcher matcher = TOKEN_SELECTOR.matcher(css);
        int rules = 0;
        while (matcher.find()) {
            String selector = matcher.group(1).trim();
            rules++;
            assertTrue(selector.contains(".code-area .text."),
                    () -> selector.trim() + " lost its .code-area .text prefix — "
                            + "the base \".code-area .text { -fx-fill }\" rule (specificity "
                            + "0-2-0) will paint this token back into the default foreground");
        }
        assertTrue(rules >= 15,
                "expected the full token rule set, found only " + rules);
    }

    private static String readDiffStyles() throws Exception {
        try (InputStream in = SyntaxHighlightingTest.class.getResourceAsStream("/diff-styles.css")) {
            assertNotNull(in, "missing stylesheet /diff-styles.css");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
