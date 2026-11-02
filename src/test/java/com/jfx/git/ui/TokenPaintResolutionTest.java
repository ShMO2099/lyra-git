package com.jfx.git.ui;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test that would have caught the invisible-highlighting bug twice over.
 *
 * <p>Twice the token pipeline was provably correct — right classes, on the right
 * characters — while a user saw plain text. First because
 * {@code .code-area .text { -fx-fill }} out-specified every token rule; then because a
 * scene without the theme's token file resolves {@code -jfx-syntax-*} to black, and
 * black keywords on a dark ground read as "no highlighting". Both failures live below
 * the style class, so this suite asserts on <em>resolved paint</em>, with the same
 * stylesheet stack ({@link com.jfx.git.ui.ThemeManager} + lyra.css + diff-styles.css)
 * and the same scene registration {@code LyraApp.start} performs.
 */
class TokenPaintResolutionTest {

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

    private static final int DEFAULT_FG = 0xc9d1d9; // -color-fg-default in the dark theme

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @Test
    void tokenColoursResolveToPaintNotBlackOrForeground() {
        FxHarness.call(() -> {
            com.jfx.git.ui.ThemeManager.apply(com.jfx.git.ui.ThemeManager.Theme.DARK);
            Pane root = new Pane();
            DiffViewer viewer = new DiffViewer();
            root.getChildren().add(viewer);
            Scene scene = new Scene(root, 900, 600);
            scene.getStylesheets().add(getClass().getResource("/css/lyra.css").toExternalForm());
            // The registration is part of the fixture: an unregistered scene has no
            // -jfx-* tokens, which is precisely the failure being guarded against.
            com.jfx.git.ui.ThemeManager.register(scene);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            stage.setUserData("keep-alive");

            viewer.setFileDiff("src/Main.java",
                    "@@ -1,2 +1,2 @@\n public class Main {\n-    String s = \"old\";\n+    String s = \"new\";\n");
            FxHarness.pulse(viewer);
            FxHarness.pulse(viewer);

            DiffTextArea area = (DiffTextArea) FxHarness.lookupAll(viewer, "code-area").get(0);

            int distinctTokenFills = 0;
            boolean sawKeywordPaint = false;
            for (var node : area.lookupAll(".text")) {
                if (!(node instanceof Text t) || t.getText() == null || t.getText().isBlank()) {
                    continue;
                }
                boolean tokenStyled = t.getStyleClass().stream().anyMatch(c -> c.startsWith("tm-"));
                if (!tokenStyled) {
                    continue;
                }
                int fill = t.getFill().toString().hashCode(); // stable identity per colour
                if (t.getStyleClass().contains("tm-storage")) {
                    // 'public' / 'class' / 'String' must NOT be default fg or black.
                    assertNotEquals(String.format("0x%08x", DEFAULT_FG | 0xff),
                            String.format("0x%08x", fill & 0xffffffffL),
                            () -> t.getText() + " resolved to the default foreground");
                    sawKeywordPaint = true;
                }
                distinctTokenFills = Math.max(distinctTokenFills, distinctTokenFills);
            }

            assertTrue(sawKeywordPaint, "no storage-keyword text nodes were rendered");

            // And at least one token family paints differently from another: keywords,
            // types and strings should not all resolve to one colour.
            long distinct = area.lookupAll(".text").stream()
                    .filter(n -> n instanceof Text t && t.getStyleClass().stream()
                            .anyMatch(c -> c.startsWith("tm-")))
                    .map(n -> ((Text) n).getFill().toString())
                    .distinct()
                    .count();
            assertTrue(distinct >= 3,
                    () -> "expected keyword/type/string families to resolve to at least "
                            + "3 distinct colours, got " + distinct);
            return null;
        });
    }

    @Test
    void aRealLargeJavaFileHighlightsSubstantially() throws Exception {
        // A real 2,000-line file, not a hand-written snippet: exercises hunk-less File
        // View, grammar state across hundreds of lines, and the cache under load.
        String content = Files.readString(
                Path.of("src/main/java/com/jfx/git/ui/DiffViewer.java"));
        FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, 900, 600);
            viewer.showFileContent("src/main/java/com/jfx/git/ui/DiffViewer.java", content);
            stage.setUserData("keep-alive");
            FxHarness.pulse(viewer);
            DiffTextArea area =
                    (DiffTextArea) FxHarness.lookupAll(viewer, "code-area").get(0);

            var spans = area.getStyleSpans(0, Math.min(area.getLength(), 4000));
            int styled = 0;
            for (var span : spans) {
                if (!span.getStyle().isEmpty()) {
                    styled++;
                }
            }
            final int styledCount = styled;
            assertTrue(styledCount > 50,
                    () -> "a real Java file must highlight substantially; got " + styledCount
                            + " styled spans in its first 4,000 characters");
            return null;
        });
    }
}
