package com.jfx.git.ui.syntax;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every regular expression in every bundled grammar compiles the way TM4E compiles it.
 *
 * <h2>Why</h2>
 * TM4E runs on joni, which rejects some look-behinds Oniguruma proper accepts: a group of
 * alternatives nested inside one, or anything variable-length. A grammar holding such a
 * pattern still loads, and still tokenises most lines - until a line makes TM4E try that
 * rule, and then the whole line comes out unhighlighted. TypeScript and JavaScript lost
 * {@code const} and {@code import} lines that way, and Rust lines as plain as
 * {@code fn main() { let x = 1; }}.
 * {@link GrammarCatalogueTest} and {@link GrammarTokenizationTest} tokenise samples, and
 * a sample reaches only the rules it happens to exercise; this compiles all of them.
 *
 * <p>A failure names the file, the pattern's place in it and joni's reason. Repair the
 * pattern - in the curated set by editing the file and recording it in
 * {@code grammars/NOTICE.md}, in the catalogue with a {@code PATCHES} entry in
 * {@code scripts/update-grammars.mjs} - rather than excluding it here.
 *
 * <p>{@link OnigRegExp} is TM4E-internal, used here on purpose: it is the exact compile
 * path, including the rewrites TM4E applies itself when joni first refuses a pattern.
 */
class GrammarPatternsCompileTest {

    private static final Path GRAMMARS = Path.of("src", "main", "resources", "grammars");

    /** The keys whose values TM4E compiles; the others (names, captures) are not patterns. */
    private static final List<String> PATTERN_KEYS = List.of("match", "begin", "end", "while");

    /**
     * TM4E substitutes a begin match's captured text for each back-reference in its end or
     * while pattern before compiling it; this stands in for that text, the same way.
     */
    private static final Pattern BACK_REFERENCE = Pattern.compile("\\\\(\\d+)");

    private record Found(String where, String key, String pattern) {}

    static Stream<Path> grammarFiles() throws Exception {
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(GRAMMARS, GRAMMARS.resolve("tm"))) {
            try (Stream<Path> listing = Files.list(dir)) {
                listing.filter(p -> {
                            String name = p.getFileName().toString();
                            return (name.endsWith(".json") && !name.equals("index.json")) || name.endsWith(".plist");
                        })
                        .sorted()
                        .forEach(files::add);
            }
        }
        assertTrue(files.size() > 150, "the curated set and the catalogue: " + files.size());
        return files.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("grammarFiles")
    void everyPatternCompiles(Path file) throws Exception {
        List<Found> patterns = new ArrayList<>();
        if (file.toString().endsWith(".plist")) {
            collectFromPlist(file, patterns);
        } else {
            collect(JsonParser.parseString(Files.readString(file)), "$", patterns);
        }
        // Some catalogue files (jinja-html, vue-directives) only include other grammars.

        List<String> rejected = new ArrayList<>();
        for (Found found : patterns) {
            String source = found.key().equals("end") || found.key().equals("while")
                    ? BACK_REFERENCE.matcher(found.pattern()).replaceAll("x")
                    : found.pattern();
            try {
                new OnigRegExp(source);
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                rejected.add(found.where() + " [" + found.key() + "]: " + cause.getMessage()
                        + "\n      " + found.pattern());
            }
        }
        assertTrue(rejected.isEmpty(), () -> file + ": " + rejected.size() + " of " + patterns.size()
                + " patterns will not compile under TM4E, and any line that reaches one comes out"
                + " unhighlighted:\n  " + String.join("\n  ", rejected));
    }

    private static void collect(JsonElement element, String where, List<Found> out) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (PATTERN_KEYS.contains(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    out.add(new Found(where, entry.getKey(), entry.getValue().getAsString()));
                } else {
                    collect(entry.getValue(), where + "." + entry.getKey(), out);
                }
            }
        } else if (element.isJsonArray()) {
            int i = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                collect(child, where + "[" + i++ + "]", out);
            }
        }
    }

    /** A plist grammar: each pattern key is a {@code <key>} followed by its {@code <string>}. */
    private static void collectFromPlist(Path file, List<Found> out) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        NodeList keys = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("key");
        for (int i = 0; i < keys.getLength(); i++) {
            String key = keys.item(i).getTextContent();
            if (!PATTERN_KEYS.contains(key)) {
                continue;
            }
            Node value = keys.item(i).getNextSibling();
            while (value != null && value.getNodeType() != Node.ELEMENT_NODE) {
                value = value.getNextSibling();
            }
            if (value instanceof Element string && string.getTagName().equals("string")) {
                out.add(new Found("key #" + i, key, string.getTextContent()));
            }
        }
    }
}
