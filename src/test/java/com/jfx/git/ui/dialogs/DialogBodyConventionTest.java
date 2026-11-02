package com.jfx.git.ui.dialogs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every dialog is in the shell, and none lays its body out in the settings grid.
 *
 * <h2>Why</h2>
 * Phase 3 of the dialog redesign moved the last hand-built forms - clone, the credential
 * prompts, add submodule and worktree, the integrations token and server - into
 * {@link JfxDialog#shell} and onto {@link DialogParts}, and every dialog class off
 * {@link JfxDialog#form()}'s label column, which in a 420px dialog put a field's left
 * edge a third of the way across the window. A new dialog written the old way would bring
 * both back without anything saying so.
 *
 * <p>{@code form()} is still right for the Settings pages, which are wide; it is only a
 * dialog that must not use it.
 */
class DialogBodyConventionTest {

    private static final Pattern NEW_DIALOG = Pattern.compile(
            "new\\s+(?:javafx\\.scene\\.control\\.)?Dialog\\s*<[^>]*>\\s*\\(");
    private static final Pattern SHELLED = Pattern.compile("JfxDialog\\.shell\\(");
    private static final Pattern GRID = Pattern.compile("JfxDialog\\.(form|addRow|addHint|addSection)\\(");

    /** Every {@code new Dialog<>()} reaches the shell within the method that builds it. */
    @Test
    void everyDialogIsInTheShell() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : sources(Path.of("src/main/java"))) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!NEW_DIALOG.matcher(lines.get(i)).find() || comment(lines.get(i))) {
                    continue;
                }
                boolean shelled = false;
                // 120 lines: a form may build its whole body before shelling it (New repository).
                for (int j = i; j < Math.min(lines.size(), i + 120) && !shelled; j++) {
                    shelled = SHELLED.matcher(lines.get(j)).find();
                }
                if (!shelled) {
                    found.add(file.getFileName() + ":" + (i + 1) + "  " + lines.get(i).strip());
                }
            }
        }
        assertTrue(found.isEmpty(), () -> "dialogs built outside the shell - pass them to JfxDialog.shell,"
                + " or use Dialogs:\n  " + String.join("\n  ", found));
    }

    /** No dialog lays its body out in the label column; DialogParts stacks it. */
    @Test
    void noDialogUsesTheSettingsGrid() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : sources(Path.of("src/main/java"))) {
            String path = file.toString().replace('\\', '/');
            if (path.contains("/ui/settings/") || path.endsWith("/ui/dialogs/JfxDialog.java")) {
                continue; // the Settings pages are where the grid belongs
            }
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (GRID.matcher(lines.get(i)).find() && !comment(lines.get(i))) {
                    found.add(file.getFileName() + ":" + (i + 1) + "  " + lines.get(i).strip());
                }
            }
        }
        assertTrue(found.isEmpty(), () -> "dialog bodies on JfxDialog.form() - use DialogParts.field and"
                + " DialogParts.stack:\n  " + String.join("\n  ", found));
    }

    private static boolean comment(String line) {
        String s = line.strip();
        return s.startsWith("*") || s.startsWith("//") || s.startsWith("/*");
    }

    private static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
