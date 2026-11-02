package com.jfx.git.ui.syntax;

import org.eclipse.tm4e.core.registry.IGrammarSource;

import java.util.List;

/**
 * One language a {@link GrammarRegistry} can load a {@link TextMateTokenizer} for -
 * either one of the curated entries in {@code GrammarRegistry.CURATED} or one row of
 * the catalogue's {@code grammars/tm/index.json}.
 *
 * @param id                   short identifier, e.g. {@code "yaml"}
 * @param displayName          human-readable name, e.g. {@code "YAML"}
 * @param fileExtensions       lower-case extensions without the leading dot, e.g. {@code "yml"}
 * @param fileNames            lower-case exact file names for files whose extension says
 *                             nothing, e.g. {@code "dockerfile"}, {@code "makefile"},
 *                             {@code ".bashrc"}; empty for most languages
 * @param grammarResourcePath  classpath resource path to the grammar file
 * @param scopeName            the grammar's own TextMate scope name, e.g. {@code source.yaml};
 *                             also what another grammar's {@code include} of this
 *                             language resolves through
 * @param contentType          how the grammar file is written — most are JSON, one
 *                             (YAML) is an XML property list, which TM4E also reads
 * @param threadState          whether lexer state carries from one line to the next.
 *                             True for almost every grammar; false for Java, whose
 *                             grammar's state does not survive TM4E's rule-stack
 *                             round trip between lines — carried forward, it collapses
 *                             everything after the first line into
 *                             {@code meta.class.body}. Line-independent lexing loses
 *                             multi-line constructs (block comments colour as code past
 *                             their first line), which is the lesser wrong by far.
 */
public record LanguageDefinition(
        String id,
        String displayName,
        List<String> fileExtensions,
        List<String> fileNames,
        String grammarResourcePath,
        String scopeName,
        IGrammarSource.ContentType contentType,
        boolean threadState) {

    /** A language that is only ever picked by extension. */
    public LanguageDefinition(String id, String displayName, List<String> fileExtensions,
                              String grammarResourcePath, String scopeName,
                              IGrammarSource.ContentType contentType, boolean threadState) {
        this(id, displayName, fileExtensions, List.of(), grammarResourcePath, scopeName,
                contentType, threadState);
    }

    /** The common case: a JSON grammar whose state threads line to line. */
    public static LanguageDefinition json(String id, String displayName,
                                          List<String> fileExtensions,
                                          String grammarResourcePath, String scopeName) {
        return json(id, displayName, fileExtensions, List.of(), grammarResourcePath, scopeName);
    }

    /** The common case, for a language some files are recognised by name. */
    public static LanguageDefinition json(String id, String displayName,
                                          List<String> fileExtensions, List<String> fileNames,
                                          String grammarResourcePath, String scopeName) {
        return new LanguageDefinition(id, displayName, fileExtensions, fileNames,
                grammarResourcePath, scopeName, IGrammarSource.ContentType.JSON, true);
    }

    /** Whether a file can open in this language at all, as opposed to it only being embedded in others. */
    public boolean isSelectable() {
        return !fileExtensions.isEmpty() || !fileNames.isEmpty();
    }
}
