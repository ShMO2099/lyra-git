package com.jfx.git.ui.syntax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrammarRegistryTest {

    private final GrammarRegistry registry = GrammarRegistry.instance();

    /**
     * Every compiled grammar used to stay for the life of the process: 519 MB once each
     * bundled language had been used, and the TM4E registry kept them whatever the cache
     * did, so bounding the cache alone freed nothing.
     */
    @Test
    void onlyTheMostRecentlyUsedGrammarsStayCompiled() throws Exception {
        java.lang.ref.WeakReference<org.eclipse.tm4e.core.grammar.IGrammar> javaGrammar = new java.lang.ref.WeakReference<>(
                ((TextMateTokenizer) registry.tokenizerForLanguage("java")).grammar());

        int opened = 0;
        for (LanguageDefinition language : registry.languages()) {
            if (opened > GrammarRegistry.MAX_LOADED) {
                break;
            }
            if (!language.id().equals("java") && registry.tokenizerForLanguage(language.id()) != null) {
                opened++;
            }
        }

        assertTrue(registry.loadedCount() <= GrammarRegistry.MAX_LOADED,
                "at most " + GrammarRegistry.MAX_LOADED + " compiled, not " + registry.loadedCount());
        for (int i = 0; i < 20 && javaGrammar.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(javaGrammar.get(), "the grammar used longest ago must go, with everything that compiled it");
        assertNotNull(registry.tokenizerForLanguage("java"), "and come back when a Java file opens again");
    }

    @Test
    void listsEveryBundledLanguage() {
        assertFalse(registry.languages().isEmpty());
        assertTrue(registry.languages().stream().anyMatch(l -> l.id().equals("yaml")));
        assertTrue(registry.languages().stream().anyMatch(l -> l.id().equals("markdown")));
        assertTrue(registry.languages().stream().anyMatch(l -> l.id().equals("xml")));
        assertTrue(registry.languages().stream().anyMatch(l -> l.id().equals("shell")));
        // The languages the hand-written lexers used to claim are grammar-backed now.
        assertTrue(registry.languages().stream().anyMatch(l -> l.id().equals("java")));
        assertTrue(registry.languages().stream().anyMatch(l -> l.id().equals("python")));
        assertTrue(registry.languages().stream().anyMatch(l -> l.id().equals("json")));
    }

    @Test
    void mapsEveryDeclaredExtensionToItsLanguage() {
        assertEquals("yaml", registry.languageForExtension("yml").id());
        assertEquals("yaml", registry.languageForExtension("yaml").id());
        assertEquals("markdown", registry.languageForExtension("md").id());
        assertEquals("xml", registry.languageForExtension("xml").id());
        assertEquals("shell", registry.languageForExtension("sh").id());
    }

    @Test
    void extensionLookupIsCaseInsensitive() {
        assertNotNull(registry.languageForExtension("YML"));
        assertNotNull(registry.languageForExtension("Yaml"));
    }

    @Test
    void unknownExtensionMapsToNoLanguage() {
        assertNull(registry.languageForExtension("doesnotexist"));
    }

    @Test
    void loadsARealTokenizerForEachCuratedGrammar() {
        // The curated fourteen, each by its first extension. The catalogue's languages
        // are covered one by one, against real samples, in GrammarCatalogueTest.
        for (LanguageDefinition language : registry.languages()) {
            if (language.grammarResourcePath().startsWith(GrammarRegistry.CATALOGUE_DIR)) {
                continue;
            }
            String path = "file." + language.fileExtensions().get(0);
            ScopedTokenizer tokenizer = registry.tokenizerForPath(path);
            assertNotNull(tokenizer, "expected a working tokenizer for " + language.id());
            assertEquals(language.displayName(), tokenizer.language());
        }
    }

    @Test
    void tokenizerForPathIsNullForAnUnknownExtension() {
        assertNull(registry.tokenizerForPath("file.doesnotexist"));
    }

    @Test
    void tokenizerForPathIsNullForAPathWithNoExtensionAndNoTellingName() {
        // "Makefile" is a telling name and highlights as make; a bare README is nothing.
        assertNull(registry.tokenizerForPath("README"));
        assertNull(registry.tokenizerForPath("src/main/LICENSE"));
    }

    @Test
    void tokenizerForPathIsNullForANullPath() {
        assertNull(registry.tokenizerForPath(null));
    }

    @Test
    void loadedGrammarsActuallyTokenizeRealContent() {
        ScopedTokenizer yaml = registry.tokenizerForPath("config.yml");
        ScopedTokenizer.Lexed lexed = yaml.lex("key: value", yaml.initialState());
        assertFalse(lexed.spans().isEmpty(), "expected the YAML grammar to produce spans for a real line");
    }
}
