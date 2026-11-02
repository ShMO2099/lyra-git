package com.jfx.git.ui.dialogs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No stock {@code Alert}, {@code TextInputDialog} or {@code ChoiceDialog} is built outside
 * {@link Dialogs}.
 *
 * <h2>Why</h2>
 * Eighty-four were, each in place: JavaFX's band with a glossy "?" or "!", the question in
 * body text, OK and Cancel, and a delete in the same blue as everything else. Moving them
 * onto {@link Dialogs} took one pass; a new one written the old way - copied from a stock
 * example, as most of those were - would bring the look back one dialog at a time, and
 * nothing would say so. This does.
 *
 * <p>A source check, like {@code DialogOwnershipTest}: most of these open from a menu deep
 * in a repository tab, and driving every one to look at it is the dialog tour's job.
 */
class StockDialogsTest {

    private static final Pattern STOCK = Pattern.compile(
            "new\\s+(?:javafx\\.scene\\.control\\.)?(Alert|TextInputDialog|ChoiceDialog)\\s*(?:<[^>]*>)?\\s*\\(");

    /** The one place allowed to build them: the facade itself. */
    private static final Path FACADE = Path.of("src/main/java/com/jfx/git/ui/dialogs/Dialogs.java");

    @Test
    void stockDialogsAreBuiltOnlyByTheFacade() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.normalize().equals(FACADE.normalize())) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = STOCK.matcher(lines.get(i));
                    if (m.find() && !lines.get(i).strip().startsWith("*") && !lines.get(i).strip().startsWith("//")) {
                        found.add(file.getFileName() + ":" + (i + 1) + "  " + lines.get(i).strip());
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), () -> "stock dialogs built outside Dialogs - use Dialogs.confirm,"
                + " destructive, warn, error, info, prompt or choose:\n  " + String.join("\n  ", found));
    }

    /** The facade does build them, or this test is guarding a name that moved. */
    @Test
    void theFacadeIsWhereTheyAreBuilt() throws IOException {
        assertTrue(STOCK.matcher(Files.readString(FACADE)).find(),
                "Dialogs.java no longer builds a stock Alert - move this test's allowance with it");
    }
}
