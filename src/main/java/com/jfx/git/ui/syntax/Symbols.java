package com.jfx.git.ui.syntax;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Finds the definition a line sits inside, for the sticky header above the diff.
 *
 * <p>This is the trick Git itself uses for the text after {@code @@}: scan upward for a
 * line that looks like the start of a definition. It is a heuristic and it is wrong
 * sometimes - a brace-heavy generic signature, a definition inside a string - but it is
 * cheap, needs no parser, and is right often enough to be worth showing.
 */
public final class Symbols {

    /** How far up to look before giving up; a definition further away is not context. */
    private static final int MAX_SCAN = 400;

    private static final Pattern JAVA_DEFINITION = Pattern.compile(
            "^\\s{0,8}(?:@\\w+\\s+)*(?:(?:public|private|protected|static|final|abstract"
                    + "|sealed|native|synchronized|default|export|async)\\s+)*"
                    + "(?:class|interface|enum|record|struct|fn|func|function|def)\\s+\\w+"
                    + "|^\\s{0,8}(?:(?:public|private|protected|static|final|abstract"
                    + "|synchronized|native|default)\\s+)+[\\w<>\\[\\],.?\\s]+\\s+\\w+\\s*\\(");

    private static final Pattern PYTHON_DEFINITION =
            Pattern.compile("^\\s*(?:async\\s+)?(?:def|class)\\s+\\w+");

    private Symbols() {
    }

    /**
     * The nearest definition at or above {@code lineNumber}, or {@code null} if none.
     *
     * @param lineNumber one-based, as line numbers are everywhere else in the viewer
     */
    public static String enclosing(String path, List<String> lines, int lineNumber) {
        Pattern pattern = patternFor(path);
        if (pattern == null || lines == null || lines.isEmpty()) {
            return null;
        }
        int start = Math.min(lineNumber, lines.size());
        int floor = Math.max(1, start - MAX_SCAN);
        for (int i = start; i >= floor; i--) {
            String line = lines.get(i - 1);
            if (line != null && pattern.matcher(line).find()) {
                return condense(line);
            }
        }
        return null;
    }

    private static Pattern patternFor(String path) {
        if (path == null) {
            return null;
        }
        String name = path.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (name.substring(dot + 1)) {
            case "java", "c", "h", "cpp", "cc", "hpp", "cs", "js", "jsx", "ts", "tsx",
                 "kt", "kts", "go", "rs", "swift", "scala", "groovy" -> JAVA_DEFINITION;
            case "py", "pyi", "pyw" -> PYTHON_DEFINITION;
            default -> null;
        };
    }

    /** Trims a signature down to something that fits on one bar. */
    private static String condense(String line) {
        String text = line.strip();
        int brace = text.indexOf('{');
        if (brace > 0) {
            text = text.substring(0, brace).strip();
        }
        if (text.endsWith(":")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.length() > 90 ? text.substring(0, 89) + "…" : text;
    }
}
