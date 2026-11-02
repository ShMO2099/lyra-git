package com.jfx.git.ui;

import javafx.css.CssParser;
import javafx.css.Rule;
import javafx.css.Stylesheet;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packaged application carries a binary {@code .bss} beside each of its stylesheets
 * (package-windows.sh / package-linux.sh generate them), and JavaFX loads that in place of
 * parsing the {@code .css}. Measured cold, the five sheets took 145 ms to parse and 50 ms
 * to load in binary form - and AtlantaFX already ships its theme as {@code .bss}, so the
 * application's own sheets were the only reason the CSS parser was loaded at all.
 *
 * <p>Two things have to hold for that to be safe, and these check both: every sheet
 * converts into a binary with the same rules, and the runtime really does prefer the
 * binary over the text beside it.
 */
class BinaryStylesheetTest {

    /** Every stylesheet the application loads from its own resources. */
    private static final List<String> SHEETS = List.of(
            "/css/components.css", "/css/lyra.css",
            "/css/theme-dark.css", "/css/theme-light.css",
            "/diff-styles.css");

    @TempDir
    Path tempDir;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @Test
    void everyStylesheetConvertsToTheSameRules() throws Exception {
        for (String sheet : SHEETS) {
            Path css = tempDir.resolve(Path.of(sheet).getFileName().toString());
            try (InputStream in = BinaryStylesheetTest.class.getResourceAsStream(sheet)) {
                assertNotNull(in, "missing stylesheet " + sheet);
                Files.copy(in, css);
            }
            Path bss = tempDir.resolve(css.getFileName().toString().replace(".css", ".bss"));

            Stylesheet.convertToBinary(css.toFile(), bss.toFile());
            Stylesheet parsed = new CssParser().parse(css.toUri().toURL());
            Stylesheet binary = Stylesheet.loadBinary(bss.toUri().toURL());

            assertNotNull(binary, sheet + " did not load back from binary");
            assertEquals(describe(parsed), describe(binary), sheet + " lost something in conversion");
        }
    }

    /** Selectors and property names, rule by rule: what a conversion could drop. */
    private static List<String> describe(Stylesheet sheet) {
        return sheet.getRules().stream().map(BinaryStylesheetTest::describe).toList();
    }

    private static String describe(Rule rule) {
        return rule.getSelectors() + " " + rule.getDeclarations().stream()
                .map(d -> d.getProperty()).toList();
    }

    @Test
    void theBinaryBesideAStylesheetIsWhatLoads() throws Exception {
        Path css = tempDir.resolve("probe.css");
        Files.writeString(css, ".probe { -fx-text-fill: #123456; }");
        Stylesheet.convertToBinary(css.toFile(), tempDir.resolve("probe.bss").toFile());
        // The text now says something else; only the binary still says #123456.
        Files.writeString(css, ".probe { -fx-text-fill: #654321; }");

        Color fill = FxHarness.call(() -> {
            Label label = new Label("probe");
            label.getStyleClass().add("probe");
            Scene scene = new Scene(new StackPane(label), 100, 50);
            scene.getStylesheets().add(css.toUri().toString());
            label.applyCss();
            return (Color) label.getTextFill();
        });

        assertEquals(Color.web("#123456"), fill, "JavaFX loaded the .css rather than the .bss beside it");
        assertTrue(Files.exists(css));
    }
}
