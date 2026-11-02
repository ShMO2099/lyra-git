package com.jfx.git.ui.syntax;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IRegistryOptions;
import org.eclipse.tm4e.core.registry.Registry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads TextMate grammars for every language the viewer highlights, and hands back a
 * {@link ScopedTokenizer} for a file.
 *
 * <p>Two sources, one registry. {@link #CURATED} is the short list kept in code, each
 * entry with a note on how its grammar behaves under TM4E; the <em>catalogue</em> under
 * {@code grammars/tm/} is the long one - a hundred and seventy-odd grammars bundled by
 * {@code scripts/update-grammars.mjs} from the {@code tm-grammars} package, described by
 * {@code grammars/tm/index.json}. Neither is a directory scanned at runtime: resources
 * inside a packaged module cannot be listed, so the index is the list.
 *
 * <p>Every grammar is registered by its scope name, and each TM4E registry is given a
 * resolver over that map, so a grammar that {@code include}s another language's scope -
 * JavaScript inside HTML, a fenced block inside Markdown, the styles in a Vue component -
 * gets it on demand. Grammars load lazily, on the first file that needs them, and only
 * the most recently used stay compiled; see {@link #MAX_LOADED}.
 *
 * <p>A grammar that fails to load - missing resource, malformed JSON, an unsupported
 * construct - is logged and treated as absent rather than thrown: a broken grammar must
 * not take down highlighting for every other file, let alone the editor itself.
 */
public final class GrammarRegistry {

    private static final Logger LOGGER = Logger.getLogger(GrammarRegistry.class.getName());

    /** The catalogue's index; see {@code scripts/update-grammars.mjs}. */
    static final String CATALOGUE_INDEX = "grammars/tm/index.json";
    /** Where the catalogue's grammar files live. */
    static final String CATALOGUE_DIR = "grammars/tm/";

    /**
     * The grammars kept by hand, with their files under {@code grammars/}. First in line
     * for an extension, ahead of the catalogue.
     */
    private static final List<LanguageDefinition> CURATED = List.of(
            // Java is the one grammar whose state must not thread: TM4E's rule-stack
            // round trip collapses everything after line one into meta.class.body.
            // See LanguageDefinition.threadState.
            new LanguageDefinition("java", "Java", List.of("java"),
                    "grammars/java.tmLanguage.json", "source.java",
                    IGrammarSource.ContentType.JSON, false),
            LanguageDefinition.json("python", "Python", List.of("py", "pyi", "pyw"),
                    "grammars/MagicPython.tmLanguage.json", "source.python"),
            LanguageDefinition.json("json", "JSON", List.of("json", "jsonc", "ipynb"),
                    List.of(".babelrc", ".eslintrc", ".prettierrc", ".stylelintrc"),
                    "grammars/JSON.tmLanguage.json", "source.json"),
            // .c goes to the catalogue's C grammar; headers stay here, since a .h is as
            // often C++ as C and the C++ grammar reads both.
            LanguageDefinition.json("cpp", "C++",
                    List.of("h", "cpp", "cc", "hpp", "cxx", "hxx", "hh", "inl", "ipp", "cu", "cuh"),
                    "grammars/cpp.tmLanguage.json", "source.cpp"),
            LanguageDefinition.json("csharp", "C#", List.of("cs"),
                    "grammars/csharp.tmLanguage.json", "source.cs"),
            LanguageDefinition.json("go", "Go", List.of("go"),
                    "grammars/go.tmLanguage.json", "source.go"),
            LanguageDefinition.json("rust", "Rust", List.of("rs"),
                    "grammars/rust.tmLanguage.json", "source.rust"),
            LanguageDefinition.json("swift", "Swift", List.of("swift"),
                    "grammars/swift.tmLanguage.json", "source.swift"),
            // .jsx and .tsx go to the catalogue's JSX and TSX grammars, which know the
            // markup; these two do not.
            LanguageDefinition.json("javascript", "JavaScript", List.of("js", "mjs", "cjs"),
                    "grammars/JavaScript.tmLanguage.json", "source.js"),
            LanguageDefinition.json("typescript", "TypeScript", List.of("ts", "mts", "cts"),
                    "grammars/TypeScript.tmLanguage.json", "source.ts"),
            // The plist form: the JSON YAML grammar bundled here previously (RedCMD's)
            // loads under TM4E but emits only its root scope — no tokens at all. This is
            // the grammar TM4E itself tests against, so it is known to work.
            new LanguageDefinition("yaml", "YAML", List.of("yml", "yaml"),
                    "grammars/yaml.tmLanguage.plist", "source.yaml",
                    IGrammarSource.ContentType.XML, true),
            LanguageDefinition.json("markdown", "Markdown", List.of("md", "markdown", "mdown", "mkd"),
                    "grammars/markdown.tmLanguage.json", "text.html.markdown"),
            // .xsl/.xslt go to the catalogue's XSL grammar.
            LanguageDefinition.json("xml", "XML",
                    List.of("xml", "xsd", "plist", "svg", "csproj", "vbproj", "fsproj", "props",
                            "targets", "resx", "xaml", "wsdl", "nuspec"),
                    "grammars/xml.tmLanguage.json", "text.xml"),
            LanguageDefinition.json("shell", "Shell Script", List.of("sh", "bash", "zsh", "ksh"),
                    List.of(".bashrc", ".zshrc", ".bash_profile", ".bash_aliases", ".bash_logout",
                            ".profile", ".zprofile", ".zshenv", ".zlogin"),
                    "grammars/shell.tmLanguage.json", "source.shell")
    );

    /**
     * TM4E's two chatty loggers, filtered; see {@link #quietenTm4e()}. Held in a field
     * because {@code java.util.logging} keeps loggers weakly and a collected logger loses
     * its filter; declared before {@link #INSTANCE} so the filters are in place before the
     * first grammar compiles.
     */
    private static final List<Logger> TM4E_LOGGERS = quietenTm4e();

    private static final GrammarRegistry INSTANCE = new GrammarRegistry();

    private final List<LanguageDefinition> languages;
    private final Map<String, LanguageDefinition> byId = new HashMap<>();
    private final Map<String, LanguageDefinition> byExtension = new HashMap<>();
    private final Map<String, LanguageDefinition> byFileName = new HashMap<>();
    private final Map<String, LanguageDefinition> byScope = new HashMap<>();

    /**
     * How many languages keep their compiled grammar between files. A compiled grammar
     * is a few megabytes - its rules, and joni's compiled scanners for every pattern it
     * has matched - and the cache used to keep every one it had compiled: 58 MB after nine
     * files in seven languages, and 519 MB once every bundled grammar had been used. A
     * repository rarely has more than a handful of languages being read at once, and one
     * that falls out costs a recompile the next time a file of its language opens.
     */
    static final int MAX_LOADED = 12;

    /**
     * Compiled grammars by language id, least recently used first, at most
     * {@link #MAX_LOADED}. Guarded by itself.
     *
     * <p>Each grammar comes from a TM4E registry of its own (see {@link #loadGrammar}), so
     * dropping one here lets it go with everything it compiled. One shared registry kept
     * every grammar it had ever produced whatever this map evicted.
     */
    private final Map<String, IGrammar> loaded = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, IGrammar> eldest) {
            return size() > MAX_LOADED;
        }
    };
    /**
     * Resolves any registered scope, so one grammar's include of another is honoured.
     *
     * <p>A scope nobody registered - Markdown names fifty languages for its fenced
     * blocks, and a few of those are not bundled - gets an empty grammar rather than
     * nothing. Given nothing, TM4E logs a warning and throws away the whole rule that
     * included it, so a fenced block of an unbundled language lost its fence colouring
     * too. Given an empty grammar, the block keeps its fences and its contents stay plain
     * text, which is what VS Code shows for a language it does not know.
     */
    private final IRegistryOptions resolver = new IRegistryOptions() {
        @Override
        public IGrammarSource getGrammarSource(String scopeName) {
            LanguageDefinition language = byScope.get(scopeName);
            if (language != null) {
                return sourceFor(language);
            }
            if (unresolved.add(scopeName)) {
                LOGGER.fine(() -> "No grammar bundled for embedded scope " + scopeName + "; left as plain text");
            }
            return IGrammarSource.fromString(IGrammarSource.ContentType.JSON, emptyGrammar(scopeName));
        }
    };

    /** Embedded scopes asked for that no bundled grammar provides; for tests and the one log line each. */
    private final java.util.Set<String> unresolved = ConcurrentHashMap.newKeySet();

    /**
     * A grammar for {@code scopeName} that matches nothing. One pattern that can never
     * match rather than none: TM4E also removes a rule whose included patterns are empty.
     */
    static String emptyGrammar(String scopeName) {
        return "{\"scopeName\":\"" + scopeName.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"patterns\":[{\"match\":\"(?!)\"}],\"repository\":{}}";
    }

    /** Embedded scopes that were asked for and are not bundled, for tests. */
    java.util.Set<String> unresolvedScopes() {
        return java.util.Set.copyOf(unresolved);
    }

    /**
     * joni's lint on patterns it compiles and runs correctly: {@code [^]]} ("has ']'
     * without escape"), {@code (?:x+)?} ("nested repeat operator ... replaced"), an
     * escape it ignores. Thousands of these from upstream grammars, every one harmless.
     */
    private static final List<String> JONI_LINT = List.of(
            "without escape", "nested repeat operator", "redundant nested repeat operator",
            "Unknown escape");

    /**
     * TM4E's notes on references a grammar makes to rules that do not exist - a
     * repository entry the grammar never defines, or one in an embedded grammar of a
     * different vintage (graphql's {@code #literal-quasi-embedded}, an older C++ grammar's
     * missing {@code #root_context}). VS Code ignores these silently; TM4E printed one
     * warning per reference, hundreds on first use of Markdown.
     */
    private static final List<String> MISSING_RULE = List.of(
            "CANNOT find rule for scopeName", "CANNOT find grammar for scopeName",
            "ENTIRELY DUE TO EMPTY PATTERNS THAT ARE MISSING");

    /**
     * Keeps TM4E's console quiet about upstream grammar quirks without losing them: joni's
     * lint is dropped, and TM4E's missing-rule notes are re-logged at FINE on this class's
     * logger, where {@code -Djava.util.logging} configuration can still show them. Any other
     * message from those loggers - a pattern that fails to compile, say - gets through.
     */
    private static List<Logger> quietenTm4e() {
        Logger onig = Logger.getLogger("org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp");
        Logger rules = Logger.getLogger("org.eclipse.tm4e.core.internal.rule.RuleFactory");
        installFilter(onig, message -> JONI_LINT.stream().anyMatch(message::contains), false);
        installFilter(rules, message -> MISSING_RULE.stream().anyMatch(message::contains), true);
        return List.of(onig, rules);
    }

    private static void installFilter(Logger logger, java.util.function.Predicate<String> quiet, boolean demote) {
        java.util.logging.Filter previous = logger.getFilter();
        logger.setFilter(record -> {
            String message = record.getMessage();
            if (message != null && quiet.test(message)) {
                if (demote && LOGGER.isLoggable(Level.FINE)) {
                    Object[] parameters = record.getParameters();
                    LOGGER.fine(parameters == null || parameters.length == 0 ? message
                            : java.text.MessageFormat.format(message, parameters));
                }
                return false;
            }
            return previous == null || previous.isLoggable(record);
        });
    }

    private GrammarRegistry() {
        // Touch the field so the filters are installed before the first grammar compiles.
        TM4E_LOGGERS.size();
        List<LanguageDefinition> all = new ArrayList<>(CURATED);
        all.addAll(catalogue());
        languages = List.copyOf(all);
        // First registration wins: the curated list is ahead of the catalogue, and the
        // catalogue is generated with no two languages claiming one extension.
        for (LanguageDefinition language : languages) {
            byId.putIfAbsent(language.id(), language);
            byScope.putIfAbsent(language.scopeName(), language);
            for (String extension : language.fileExtensions()) {
                byExtension.putIfAbsent(extension.toLowerCase(Locale.ROOT), language);
            }
            for (String name : language.fileNames()) {
                byFileName.putIfAbsent(name.toLowerCase(Locale.ROOT), language);
            }
        }
    }

    public static GrammarRegistry instance() {
        return INSTANCE;
    }

    /** One row of {@code grammars/tm/index.json}; the fields the registry reads. */
    private record CatalogueEntry(String id, String displayName, String scopeName, String file,
                                  List<String> extensions, List<String> fileNames) {
    }

    /** The catalogue as language definitions; empty, with a warning, if the index is missing or broken. */
    private static List<LanguageDefinition> catalogue() {
        try (InputStream in = openResource(CATALOGUE_INDEX)) {
            if (in == null) {
                LOGGER.warning("Grammar catalogue index not found: " + CATALOGUE_INDEX);
                return List.of();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<CatalogueEntry> entries = new Gson().fromJson(json,
                    new TypeToken<List<CatalogueEntry>>() { }.getType());
            List<LanguageDefinition> out = new ArrayList<>(entries.size());
            for (CatalogueEntry entry : entries) {
                out.add(LanguageDefinition.json(entry.id(), entry.displayName(),
                        entry.extensions() == null ? List.of() : List.copyOf(entry.extensions()),
                        entry.fileNames() == null ? List.of() : List.copyOf(entry.fileNames()),
                        CATALOGUE_DIR + entry.file(), entry.scopeName()));
            }
            return out;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to read the grammar catalogue index", e);
            return List.of();
        }
    }

    /** Every language this registry knows about, curated first, whether or not its grammar has loaded yet. */
    public List<LanguageDefinition> languages() {
        return languages;
    }

    /** The language registered under {@code id}, if any. */
    public LanguageDefinition language(String id) {
        return id == null ? null : byId.get(id);
    }

    /** The language registered for {@code extension} (no leading dot), if any. */
    public LanguageDefinition languageForExtension(String extension) {
        return extension == null ? null : byExtension.get(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * The language for {@code path}: by the file's exact name first ({@code Dockerfile},
     * {@code Makefile}, {@code .bashrc}), then by its extension; {@code null} when neither
     * is registered.
     */
    public LanguageDefinition languageForPath(String path) {
        if (path == null) {
            return null;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String base = lower.substring(slash + 1);
        LanguageDefinition named = byFileName.get(base);
        if (named != null) {
            return named;
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return byExtension.get(base.substring(dot + 1));
    }

    /**
     * A tokenizer for {@code path}, or {@code null} when no grammar is registered for
     * it, or the grammar failed to load.
     */
    public ScopedTokenizer tokenizerForPath(String path) {
        LanguageDefinition language = languageForPath(path);
        return language == null ? null : tokenizerFor(language);
    }

    /** A tokenizer for the language registered under {@code id}; {@code null} when unknown or broken. */
    public ScopedTokenizer tokenizerForLanguage(String id) {
        LanguageDefinition language = language(id);
        return language == null ? null : tokenizerFor(language);
    }

    private ScopedTokenizer tokenizerFor(LanguageDefinition language) {
        IGrammar grammar;
        synchronized (loaded) {
            grammar = loaded.get(language.id());
        }
        if (grammar == null) {
            // Compiled outside the lock: a large grammar takes a while, and a file in
            // another language need not wait for it. Two threads asking for the same new
            // language may both compile it; the first one in is kept.
            grammar = loadGrammar(language);
            if (grammar == null) {
                return null;
            }
            synchronized (loaded) {
                IGrammar first = loaded.putIfAbsent(language.id(), grammar);
                if (first != null) {
                    grammar = first;
                }
            }
        }
        return new TextMateTokenizer(language.displayName(), grammar, language.threadState());
    }

    /** How many languages have a compiled grammar cached right now; for tests. */
    int loadedCount() {
        synchronized (loaded) {
            return loaded.size();
        }
    }

    private IGrammar loadGrammar(LanguageDefinition language) {
        try {
            // Through a registry's resolver rather than addGrammar(source): that is what
            // lets the grammar's includes of other scopes be loaded alongside it. A
            // registry per language, because a TM4E registry keeps every grammar it has
            // produced: the grammar holds its own, so the two go when the cache lets go.
            IGrammar grammar = new Registry(resolver).loadGrammar(language.scopeName());
            if (grammar == null) {
                LOGGER.warning("Grammar could not be loaded for " + language.id()
                        + " (" + language.grammarResourcePath() + ")");
                return null;
            }
            warmUp(grammar, language);
            return grammar;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load grammar for " + language.id(), e);
            return null;
        }
    }

    /** The grammar file behind {@code language} as a TM4E source; {@code null} when the resource is missing. */
    private static IGrammarSource sourceFor(LanguageDefinition language) {
        try (InputStream in = openResource(language.grammarResourcePath())) {
            if (in == null) {
                LOGGER.warning("Grammar resource not found: " + language.grammarResourcePath());
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return IGrammarSource.fromString(language.contentType(), text);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read grammar " + language.grammarResourcePath(), e);
            return null;
        }
    }

    /**
     * Opens a bundled resource, from whichever module system is running us.
     *
     * <p>The app launches with {@code module-info} on the module path, and under JPMS
     * {@code ClassLoader.getResourceAsStream} only finds resources in packages that are
     * <em>open</em> — {@code grammars/} holds no classes, so it was never opened, and
     * every lookup returned null. The result was a warning per file and no highlighting
     * at all in the packaged app, while the classpath-launched test suite saw every
     * grammar fine. Reading through the {@link Module} instead makes the same-module
     * lookup unconditional; the classloader stays as a fallback for runners that put
     * this code on the classpath.
     */
    private static InputStream openResource(String resourcePath) {
        try {
            InputStream in = GrammarRegistry.class.getModule().getResourceAsStream(resourcePath);
            if (in != null) {
                return in;
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Module lookup failed for " + resourcePath, e);
        }
        return GrammarRegistry.class.getClassLoader().getResourceAsStream(resourcePath);
    }

    /**
     * Pays the grammar's one-time rule-compilation cost now, off the render path.
     *
     * <p>TM4E compiles a grammar's rules lazily inside the first {@code tokenizeLine},
     * against that call's time budget — and a spent budget returns a silently degraded
     * result rather than an error. Without this warm-up, the first file opened in a
     * language highlighted as one undifferentiated blob whenever compilation outlasted
     * the limit, which looked like highlighting "randomly not working".
     */
    private void warmUp(IGrammar grammar, LanguageDefinition language) {
        try {
            grammar.tokenizeLine("warm up", null, null);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                    "Warm-up tokenization failed for " + language.id(), e);
        }
    }
}
