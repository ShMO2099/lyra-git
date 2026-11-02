package com.jfx.git.ui.syntax;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Adapts a TM4E {@link IGrammar} to the {@link ScopedTokenizer} contract.
 *
 * <p>TM4E's own {@code tokenizeLine(line, previousState, timeLimit)} already lexes one
 * line at a time and hands back the state to feed into the next, which is the same shape
 * {@link ScopedTokenizer} was designed around - this class is mostly a translation of
 * types, not new lexing logic.
 */
final class TextMateTokenizer implements ScopedTokenizer {

    private static final Logger LOGGER = Logger.getLogger(TextMateTokenizer.class.getName());
    /**
     * Caps a single line's tokenization. Generous on purpose, and paired with the
     * warm-up in {@code GrammarRegistry}: TM4E enforces this budget against the whole
     * rule-compilation a cold grammar performs on its first line, and a spent budget
     * returns a silently degraded single-span result rather than an error — which read,
     * at 50 ms, as "highlighting randomly does not work".
     */
    private static final Duration TIME_LIMIT = Duration.ofMillis(500);

    private final String language;
    private final IGrammar grammar;
    /** See {@link LanguageDefinition#threadState}. */
    private final boolean threadState;

    TextMateTokenizer(String language, IGrammar grammar, boolean threadState) {
        this.language = language;
        this.grammar = grammar;
        this.threadState = threadState;
    }

    /** The grammar this tokenizes with; for tests. */
    IGrammar grammar() {
        return grammar;
    }

    @Override
    public boolean threadsState() {
        return threadState;
    }

    @Override
    public String language() {
        return language;
    }

    /**
     * {@code null} is TM4E's own convention for "no previous state" - confirmed against
     * the real grammar, not assumed: the one-argument {@code tokenizeLine(String)}
     * overload (which takes no state) and the three-argument overload called with a
     * {@code null} state produce identical results for a first line.
     */
    @Override
    public Object initialState() {
        return null;
    }

    @Override
    public Lexed lex(String line, Object state) {
        // A grammar whose state does not round-trip through TM4E (see
        // LanguageDefinition.threadState) is lexed line-independent: every line from
        // the initial state. The caller may still hand us the previous line's state;
        // it is ignored on purpose.
        Object effectiveState = threadState ? state : initialState();
        ITokenizeLineResult<IToken[]> result;
        try {
            result = grammar.tokenizeLine(line, (IStateStack) effectiveState, TIME_LIMIT);
        } catch (RuntimeException e) {
            // One line a grammar cannot stomach must not take down the whole render:
            // the line shows unstyled and the rest of the file keeps its colours.
            LOGGER.warning("Tokenization failed for " + language + ": " + e);
            return new Lexed(List.of(), effectiveState);
        }

        List<Span> spans = new ArrayList<>();
        for (IToken token : result.getTokens()) {
            spans.add(new Span(token.getStartIndex(), token.getEndIndex(),
                    styleClassesFor(token.getScopes())));
        }
        // A grammar that doesn't thread state (see LanguageDefinition.threadState) is
        // always lexed from the initial state - reporting its own returned rule stack
        // here instead would still be correct, but would vary the caller's cache key
        // (Highlighter.lexScoped) by a value this tokenizer will ignore as input on the
        // next call, defeating the cache for every line of that language.
        Object stateOut = threadState ? result.getRuleStack() : effectiveState;
        return new Lexed(spans, stateOut);
    }

    /**
     * Turns the innermost (most specific) scope of a token into a chain of style classes,
     * from broadest to narrowest - {@code string.quoted.double.json} becomes {@code
     * tm-string}, {@code tm-string-quoted}, {@code tm-string-quoted-double}, {@code
     * tm-string-quoted-double-json} - so a theme can colour at whichever level it defines
     * a rule for, without a custom "most specific match" resolver: several classes are
     * simply applied at once, and CSS's own cascade picks the one that has a rule.
     *
     * <p>Only the innermost scope is used, not the whole scope stack a token carries.
     * Every token in, say, a JSON file also carries the outer {@code source.json} scope;
     * folding that in as well would add {@code tm-source}/{@code tm-source-json} to every
     * single span for no visual benefit.
     */
    static List<String> styleClassesFor(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        String innermost = scopes.get(scopes.size() - 1);
        String[] parts = innermost.split("\\.");

        Set<String> classes = new LinkedHashSet<>(parts.length);
        StringBuilder prefix = new StringBuilder("tm");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            prefix.append('-').append(part);
            classes.add(prefix.toString());
        }
        return List.copyOf(classes);
    }
}
