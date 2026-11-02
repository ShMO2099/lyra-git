package com.jfx.git.ui.syntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grammar catalogue under {@code grammars/tm}: every language in it tokenises its
 * upstream sample into real scopes, the index is unambiguous, files are recognised by
 * name as well as extension, and one grammar's include of another resolves.
 *
 * <p>Tokenising, not merely loading, for the reason {@link GrammarTokenizationTest}
 * gives: a grammar can load under TM4E and emit nothing but its root scope. Each sample
 * must produce spans in at least two of the scope families a theme colours - keywords,
 * strings, comments, constants, storage, entities, support, variables, markup - which
 * no file-sized sample of a working grammar fails to do.
 */
class GrammarCatalogueTest {

    private static final GrammarRegistry REGISTRY = GrammarRegistry.instance();

    /** The scope families diff-styles.css colours; a span in none of these is uncoloured text. */
    private static final List<String> COLOURED = List.of(
            "tm-keyword", "tm-string", "tm-comment", "tm-constant", "tm-storage", "tm-entity",
            "tm-support", "tm-variable", "tm-markup", "tm-log");

    private static boolean inCatalogue(LanguageDefinition language) {
        return language.grammarResourcePath().startsWith(GrammarRegistry.CATALOGUE_DIR);
    }

    private static List<String> sampleFor(String id) throws IOException {
        try (InputStream in = GrammarCatalogueTest.class.getResourceAsStream("/grammar-samples/" + id + ".sample")) {
            if (in == null) {
                return null;
            }
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n"));
        }
    }

    static Stream<String> languagesWithSamples() throws IOException {
        List<String> ids = new ArrayList<>();
        for (LanguageDefinition language : REGISTRY.languages()) {
            if (inCatalogue(language) && language.isSelectable() && sampleFor(language.id()) != null) {
                ids.add(language.id());
            }
        }
        assertTrue(ids.size() > 100, "the catalogue should be large: " + ids.size());
        return ids.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("languagesWithSamples")
    void everyCatalogueLanguageTokenisesItsSample(String id) throws IOException {
        ScopedTokenizer tokenizer = REGISTRY.tokenizerForLanguage(id);
        assertNotNull(tokenizer, id + ": the grammar must load");

        Map<String, Integer> families = new HashMap<>();
        Object state = tokenizer.initialState();
        for (String line : sampleFor(id)) {
            if (line.length() > Highlighter.MAX_LINE_LENGTH) {
                continue;
            }
            ScopedTokenizer.Lexed lexed = tokenizer.lex(line, state);
            for (ScopedTokenizer.Span span : lexed.spans()) {
                for (String family : COLOURED) {
                    if (span.styleClasses().stream().anyMatch(c -> c.startsWith(family))) {
                        families.merge(family, 1, Integer::sum);
                        break;
                    }
                }
            }
            state = lexed.stateOut();
        }
        // Two families, or plenty of one: a log grammar legitimately scopes everything
        // under log.*, which is one family however many spans it fills.
        int spans = families.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(families.size() >= 2 || spans >= 10,
                () -> id + ": the sample tokenised into " + families
                        + " - a grammar that loads but does not tokenise; exclude it in"
                        + " scripts/update-grammars.mjs or fix its registration");
    }

    @Test
    void theCatalogueIsLargeAndItsFilesArePresent() throws IOException {
        int catalogued = 0;
        for (LanguageDefinition language : REGISTRY.languages()) {
            if (!inCatalogue(language)) {
                continue;
            }
            catalogued++;
            try (InputStream in = GrammarCatalogueTest.class.getClassLoader()
                    .getResourceAsStream(language.grammarResourcePath())) {
                assertNotNull(in, language.id() + ": " + language.grammarResourcePath() + " is missing");
            }
        }
        assertTrue(catalogued > 150, "catalogued grammars: " + catalogued);
    }

    @Test
    void scopesExtensionsAndFileNamesAreUnambiguous() {
        Set<String> scopes = new HashSet<>();
        Map<String, String> extensions = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (LanguageDefinition language : REGISTRY.languages()) {
            assertTrue(scopes.add(language.scopeName()),
                    "two languages register " + language.scopeName());
            for (String extension : language.fileExtensions()) {
                String other = extensions.put(extension, language.id());
                assertNull(other, "." + extension + " is claimed by " + other + " and " + language.id());
            }
            for (String name : language.fileNames()) {
                String other = names.put(name, language.id());
                assertNull(other, name + " is claimed by " + other + " and " + language.id());
            }
        }
    }

    @Test
    void filesAreRecognisedByNameAsWellAsExtension() {
        assertEquals("docker", REGISTRY.languageForPath("Dockerfile").id());
        assertEquals("docker", REGISTRY.languageForPath("services/api/Dockerfile").id());
        assertEquals("make", REGISTRY.languageForPath("Makefile").id());
        assertEquals("cmake", REGISTRY.languageForPath("lib\\CMakeLists.txt").id(), "a Windows path");
        assertEquals("shell", REGISTRY.languageForPath(".bashrc").id());
        assertEquals("groovy", REGISTRY.languageForPath("Jenkinsfile").id());
        assertEquals("kotlin", REGISTRY.languageForPath("src/main/App.kt").id());
        assertEquals("toml", REGISTRY.languageForPath("Cargo.lock").id(), "a lock file that is really TOML");
        assertNull(REGISTRY.languageForPath("README"), "no extension and no telling name");
        assertNull(REGISTRY.languageForPath("notes.unknownext"));
    }

    @Test
    void theCuratedGrammarsAndTheCatalogueShareTheExtensionsOut() {
        assertEquals("c", REGISTRY.languageForPath("main.c").id(), "C goes to the C grammar");
        assertEquals("cpp", REGISTRY.languageForPath("main.h").id(), "headers stay with C++");
        assertEquals("tsx", REGISTRY.languageForPath("App.tsx").id(), "TSX knows the markup; TypeScript's grammar does not");
        assertEquals("jsx", REGISTRY.languageForPath("App.jsx").id());
        assertEquals("javascript", REGISTRY.languageForPath("index.mjs").id());
        assertEquals("xsl", REGISTRY.languageForPath("t.xslt").id());
        assertEquals("xml", REGISTRY.languageForPath("App.csproj").id());
    }

    private static boolean tokenisesAs(ScopedTokenizer tokenizer, List<String> lines, String needle, String family) {
        Object state = tokenizer.initialState();
        for (String line : lines) {
            ScopedTokenizer.Lexed lexed = tokenizer.lex(line, state);
            for (ScopedTokenizer.Span span : lexed.spans()) {
                String covered = line.substring(span.start(), Math.min(span.end(), line.length()));
                if (covered.contains(needle) && span.styleClasses().stream().anyMatch(c -> c.startsWith(family))) {
                    return true;
                }
            }
            state = lexed.stateOut();
        }
        return false;
    }

    @Test
    void embeddedLanguagesResolveThroughTheRegistry() {
        // HTML includes source.js for its script elements, which only the registry's
        // scope resolver can supply; without it the script body is plain text.
        ScopedTokenizer html = REGISTRY.tokenizerForPath("index.html");
        assertNotNull(html);
        assertTrue(tokenisesAs(html, List.of("<html><body>", "<script>", "function f() { return 1; }",
                        "</script>", "</body></html>"), "return", "tm-keyword"),
                "JavaScript inside HTML is highlighted as JavaScript");
        assertTrue(tokenisesAs(html, List.of("<style>", "body { color: red; }", "</style>"),
                        "color", "tm-support"),
                "CSS inside HTML is highlighted as CSS");

        // And the curated Markdown grammar reaches the curated Java grammar the same way.
        ScopedTokenizer markdown = REGISTRY.tokenizerForPath("README.md");
        assertNotNull(markdown);
        assertTrue(tokenisesAs(markdown, List.of("# Title", "", "```java", "public class A {}", "```"),
                        "public", "tm-storage"),
                "a fenced Java block is highlighted as Java");
    }

    @Test
    void everyCatalogueLanguageHasADisplayNameAndAScope() {
        for (LanguageDefinition language : REGISTRY.languages()) {
            assertTrue(language.displayName() != null && !language.displayName().isBlank(), language.id());
            assertTrue(language.scopeName() != null && language.scopeName().contains("."), language.id());
        }
    }
}
