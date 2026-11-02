package com.jfx.git.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words on the screen follow one convention.
 *
 * <p>Read off the source because a wording slip is not a behaviour a robot test notices:
 * a control's label is a sentence: "Stage hunk", "Edit this file", "Open externally".
 * The diff viewer was written in Title Case ("Revert Hunk", "File View") inside an
 * application that was otherwise sentence case, and the seam showed. Menus are not
 * checked - a menu bar's items are conventionally titled.
 */
class UserFacingCopyTest {

    private static final Path UI = Path.of("src/main/java/com/jfx/git");

    /** Words that are capitalised wherever they stand. */
    private static final Set<String> PROPER = Set.of(
            "Git", "GitHub", "GitLab", "Bitbucket", "LFS", "GPG", "SSH", "HEAD", "URL", "URLs",
            "JGit", "Lyra", "I", "OK", "ID", "CI", "PR", "MR", "API", "UTF-8", "CRLF", "LF",
            "Enter", "Esc", "Ctrl", "Shift", "Alt", "Tab", "Favorites", "Integrations",
            "Preferences", "Settings", "Windows", "OpenSSH", "Explorer");

    /** A string literal handed straight to a control that shows it as its label. */
    private static final Pattern CONTROL_LABEL = Pattern.compile(
            "(?:new (?:Button|ToggleButton|CheckBox|RadioButton|Hyperlink)|flatButton|segmentedToggle"
                    + "|createButton|createSplitButton)\\(\\s*\"([^\"\\\\]+)\"");

    @Test
    void controlLabelsAreSentenceCase() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            int line = 0;
            for (String text : Files.readAllLines(file)) {
                line++;
                if (isComment(text)) continue;
                Matcher labels = CONTROL_LABEL.matcher(text);
                while (labels.find()) {
                    String label = labels.group(1);
                    String[] words = label.split("\\s+");
                    for (int i = 1; i < words.length; i++) {
                        String word = words[i].replaceAll("[^\\p{L}\\p{N}-]", "");
                        // A word with a dot in it is a file's name, written as the file is.
                        if (word.length() > 1 && Character.isUpperCase(word.charAt(0))
                                && !words[i].contains(".")
                                && !PROPER.contains(word) && !word.equals(word.toUpperCase())) {
                            offenders.add(file.getFileName() + ":" + line + " \"" + label + "\"");
                            break;
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "control labels should be sentence case:\n  " + String.join("\n  ", offenders));
    }

    private static List<Path> javaFiles() throws IOException {
        try (var paths = Files.walk(UI)) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static boolean isComment(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
    }
}
