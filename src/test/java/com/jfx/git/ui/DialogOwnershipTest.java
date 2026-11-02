package com.jfx.git.ui;

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
 * Every dialog the application creates is given an owner (see {@link DialogOwner}).
 *
 * <h2>The bug this pins</h2>
 * A dialog without an owner is a top-level window of its own: on Windows each one got its
 * own taskbar button, so a confirmation or a rename turned up as one more Lyra on the
 * taskbar, and could open behind the main window. Most dialogs reach their owner through
 * {@code JfxDialog.shell} or {@code ThemeManager.style}; this checks that every
 * {@code new Alert}, {@code TextInputDialog}, {@code ChoiceDialog} and {@code Dialog} in
 * the sources passes through one of them, or {@code DialogOwner.adopt}, before it shows.
 *
 * <p>A source check rather than a runtime one: most of these open from a menu deep in a
 * repository tab, and a test would have to drive every one of them to see it.
 *
 * <p>Three ways dialogs slipped past it before (found by a QA pass on 2026-10-13): a
 * construction written fully qualified ({@code new javafx.scene.control.Alert(...)}); one
 * whose {@code Type name =} sits on the line above the {@code new}; and
 * {@code ThemeManager.style(dialog.getDialogPane())}, which themes the pane but - unlike
 * {@code style(dialog)} - never sets an owner.
 */
class DialogOwnershipTest {

    private static final Pattern CREATE = Pattern.compile(
            "new\\s+(?:javafx\\.scene\\.control\\.)?"
                    + "(Alert|TextInputDialog|ChoiceDialog\\s*<[^>]*>|Dialog\\s*<[^>]*>)\\s*\\(");
    /** The variable a creation is assigned to, on its line or - for a ternary - a line or two above. */
    private static final Pattern ASSIGNED = Pattern.compile("(\\w+)\\s*=\\s*(?:[^=;]*\\?\\s*)?$");

    @Test
    void everyDialogIsGivenAnOwner() throws IOException {
        List<String> unowned = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = CREATE.matcher(lines.get(i));
                    while (m.find()) {
                        if (!owned(lines, i, m.start())) {
                            unowned.add(file.getFileName() + ":" + (i + 1) + "  " + lines.get(i).strip());
                        }
                    }
                }
            }
        }
        assertTrue(unowned.isEmpty(), () -> "dialogs that never reach DialogOwner (wrap the creation in"
                + " DialogOwner.adopt, or pass the dialog - not its pane - to ThemeManager.style /"
                + " JfxDialog.shell):\n  "
                + String.join("\n  ", unowned));
    }

    private static boolean owned(List<String> lines, int line, int column) {
        String before = lines.get(line).substring(0, column);
        // `new` opens its line: the assignment, or the call it is passed to, ends the one above.
        if (before.isBlank() && line > 0) before = lines.get(line - 1).stripTrailing();
        if (before.matches(".*(adopt|style|shell)\\(\\s*$")) return true;
        String var = assignedTo(lines, line, before);
        if (var == null) return false;
        // The dialog itself, not `var.getDialogPane()`: styling the pane sets no owner.
        Pattern owner = Pattern.compile("(style|shell|adopt)\\(\\s*" + Pattern.quote(var) + "\\s*[,)]");
        for (int j = line; j < Math.min(lines.size(), line + 80); j++) {
            if (owner.matcher(lines.get(j)).find()) return true;
        }
        return false;
    }

    private static String assignedTo(List<String> lines, int line, String before) {
        Matcher m = ASSIGNED.matcher(before);
        if (m.find()) return m.group(1);
        // `Alert alert = cond` on an earlier line, then `? new Alert(...)` / `: new Alert(...)`.
        String trimmed = before.strip();
        if (trimmed.equals("?") || trimmed.equals(":")) {
            for (int j = line - 1; j >= Math.max(0, line - 3); j--) {
                Matcher up = Pattern.compile("(\\w+)\\s*=").matcher(lines.get(j));
                if (up.find()) return up.group(1);
            }
        }
        return null;
    }
}
