package com.jfx.git;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.Registry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feasibility spike, not a permanent fixture: answers whether {@code org.eclipse.tm4e.core}
 * resolves and tokenizes correctly under this project's real Maven/JPMS setup, before any
 * larger commitment to a TextMate-grammar-based editor (see EDITOR_IMPLEMENTATION_SPEC.md,
 * HARDENING_ROADMAP.md).
 *
 * <p>Findings recorded here rather than in a separate document, since the test itself is
 * the evidence:
 * <ul>
 *   <li>No stable release exists. The real coordinate is {@code org.eclipse:org.eclipse.tm4e.core}
 *       (not {@code org.eclipse.tm4e:org.eclipse.tm4e.core} as a first guess from the artifact
 *       ID would suggest) - the {@code 0.18.0-SNAPSHOT} line published under
 *       {@code https://repo.eclipse.org/content/repositories/tm4e-snapshots/}, confirmed
 *       against that repository's own {@code maven-metadata.xml} rather than assumed. The
 *       pom pins one timestamped build of it rather than the moving alias; see the note
 *       on the dependency for why and which.</li>
 *   <li>It resolves with Maven, but as a <b>plain, non-modular jar</b>: its manifest carries
 *       {@code Automatic-Module-Name: org.eclipse.tm4e.core}, not a real {@code module-info.class}
 *       (confirmed by inspecting the actual jar's contents, not the project's documentation).
 *       It could not be added to {@code module-info.java}'s {@code requires} list without
 *       becoming an automatic module in a project that is otherwise fully modular - the same
 *       category of packaging problem the project's own {@code pom.xml} already flags for
 *       the JGit SSH transport blocking {@code jlink}. This test only needs it on the test
 *       classpath, which Surefire already uses (no {@code src/test/java/module-info.java}
 *       exists), so that tension does not have to be resolved to run this spike.</li>
 *   <li>Its real dependencies are declared as OSGi {@code Require-Bundle} entries in the
 *       manifest (gson &gt;= 2.13.1, Batik CSS/util, jcodings, joni, snakeyaml-engine), which
 *       plain Maven does not follow - {@code mvn dependency:tree} shows this as a leaf with
 *       zero transitive dependencies. This is not a formality: the very first
 *       {@code tokenizeLine()} call threw {@code NoClassDefFoundError} for
 *       {@code org.joni.exception.SyntaxException} until {@code joni} and its own
 *       dependency {@code jcodings} were added explicitly - {@code joni} is the actual
 *       Oniguruma-syntax regex engine TextMate patterns are written against, not an
 *       optional extra. gson/Batik/snakeyaml-engine were not needed for this plain
 *       pattern-matching path with no CSS theme involved, but a real {@code CodeEditor}
 *       build should verify those separately on whatever code path actually uses them,
 *       not assume this test proves their absence is safe everywhere.</li>
 *   <li>The real API - confirmed via {@code javap} against the resolved jar, not guessed -
 *       is exactly {@code Registry.addGrammar(IGrammarSource)},
 *       {@code IGrammarSource.fromString(ContentType, String)},
 *       {@code IGrammar.tokenizeLine(String)} returning
 *       {@code ITokenizeLineResult<IToken[]>}, and {@code IToken.getScopes()}.</li>
 * </ul>
 *
 * <p>The grammar and expected scopes below are copied verbatim from TM4E's own upstream
 * test fixture ({@code first-mate/fixtures/hello.json}) and its paired expectation in
 * {@code first-mate/tests.json}, not freehanded - a subtly malformed grammar would fail
 * this spike for the wrong reason (a bad fixture, not a real TM4E problem).
 */
class Tm4eSpikeTest {

    private static final String HELLO_GRAMMAR = """
            {
            \t"name": "hello",
            \t"scopeName": "source.hello",
            \t"fileTypes": [
            \t\t"world"
            \t],
            \t"patterns": [
            \t\t{
            \t\t\t"match": "hello",
            \t\t\t"name": "prefix.hello"
            \t\t},
            \t\t{
            \t\t\t"match": "world(!?)",
            \t\t\t"captures": {
            \t\t\t\t"1": {
            \t\t\t\t\t"name": "emphasis.hello"
            \t\t\t\t}
            \t\t\t},
            \t\t\t"name": "suffix.hello"
            \t\t}
            \t]
            }
            """;

    @Test
    void resolvesLoadsAndTokenizesTheUpstreamHelloFixture() throws Exception {
        Registry registry = new Registry();
        IGrammarSource source = IGrammarSource.fromString(
                IGrammarSource.ContentType.JSON, HELLO_GRAMMAR);
        IGrammar grammar = registry.addGrammar(source);
        assertNotNull(grammar, "grammar failed to load");
        assertEquals("source.hello", grammar.getScopeName());

        ITokenizeLineResult<IToken[]> result = grammar.tokenizeLine("hello world!");
        List<IToken> tokens = List.of(result.getTokens());
        assertTrue(tokens.size() >= 3, "expected at least 3 tokens, got: " + tokens.size());

        // "hello" -> ["source.hello", "prefix.hello"], per tests.json's own expectation.
        IToken helloToken = tokens.get(0);
        assertEquals(0, helloToken.getStartIndex());
        assertEquals(5, helloToken.getEndIndex());
        assertTrue(helloToken.getScopes().contains("prefix.hello"),
                "expected prefix.hello in " + helloToken.getScopes());
        assertTrue(helloToken.getScopes().contains("source.hello"),
                "expected the base scope in " + helloToken.getScopes());

        // "world!" -> the "!" carries the extra emphasis.hello scope from the capture group.
        IToken lastToken = tokens.get(tokens.size() - 1);
        assertTrue(lastToken.getScopes().contains("emphasis.hello"),
                "expected emphasis.hello on the final token, got " + lastToken.getScopes());
        assertTrue(lastToken.getScopes().contains("suffix.hello"),
                "expected suffix.hello on the final token, got " + lastToken.getScopes());
    }
}
